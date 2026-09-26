package com.djzy.assistant.gateway.web;

import com.djzy.assistant.common.api.ApiCallRequest;
import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.ApiRegistry;
import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.confirm.ConfirmStore;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.permission.ApiCallDecision;
import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.web.auth.CurrentUser;
import com.djzy.assistant.common.web.auth.UserContext;
import com.djzy.assistant.gateway.core.CircuitBreakerRegistry;
import com.djzy.assistant.gateway.core.DownstreamClient;
import com.djzy.assistant.gateway.core.DownstreamResponse;
import com.djzy.assistant.gateway.core.DownstreamUnavailableException;
import com.djzy.assistant.gateway.core.RedisUserRateLimiter;
import com.djzy.assistant.gateway.core.ResponseTruncator;
import com.djzy.assistant.gateway.core.UserRateLimiter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关数据调用入口：G4 限额 → G2′ 路由解析 → G3 判定 → G2 转发（§18.4.2）。
 *
 * <p>本方法只剩业务主流程，三件与业务无关的事都已经挪走：
 * <ul>
 *   <li><b>G1 身份解析</b> → {@link com.djzy.assistant.common.web.auth.UserContextInterceptor}
 *       （验不过的请求根本进不到这里，所以本方法只取 userId 用，§20.1.6-5）；</li>
 *   <li><b>G5 审计记账</b> → {@link PermissionAuditAspect}：这里只调 {@code audit.allow()} /
 *       {@code audit.deny(原因)} 下结论，怎么落库不归它管；</li>
 *   <li><b>限速计数、熔断状态</b> → 各自组件，这里只问一句「还行不行」。</li>
 * </ul>
 *
 * <p>关键点一：**接口身份是三元组**「服务 + 方法 + 路径」，不是某个接口编码。G2′ 用三元组精确查
 * 注册行，查到行才谈得上"这个用户有没有这把钥匙"（G3 拿 {@code sys_api.id} 去比 {@code role_api}）。
 * 查不到行 = 这扇门没登记过；登记了但停用 = 也不放行。两种都对外返回同一措辞，差异只进审计。
 *
 * <p>关键点二：转发地址来自**注册行**（{@link com.djzy.assistant.gateway.core.ServiceRouter}），
 * 不来自请求体——见那里的说明。
 */
@RestController
public class GatewayDataController {

    private static final Logger log = LoggerFactory.getLogger(GatewayDataController.class);

    private final ApiRegistry apiRegistry;
    private final AuthorizationService authorizationService;
    private final ConfirmStore confirmStore;
    private final UserRateLimiter rateLimiter;
    private final CircuitBreakerRegistry breakers;
    private final DownstreamClient downstream;
    private final ResponseTruncator truncator;

    public GatewayDataController(
            ApiRegistry apiRegistry,
            AuthorizationService authorizationService,
            ConfirmStore confirmStore,
            UserRateLimiter rateLimiter,
            CircuitBreakerRegistry breakers,
            DownstreamClient downstream,
            ResponseTruncator truncator) {
        this.apiRegistry = apiRegistry;
        this.authorizationService = authorizationService;
        this.confirmStore = confirmStore;
        this.rateLimiter = rateLimiter;
        this.breakers = breakers;
        this.downstream = downstream;
        this.truncator = truncator;
    }

    @PermissionAudited
    @PostMapping(path = "/v1/gateway/api-call", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> apiCall(
            @RequestBody(required = false) ApiCallRequest request,
            @CurrentUser UserContext userContext,
            PermissionAuditRecorder audit) {
        // 入参契约：三元组与 requestId 缺一不可，缺了连"调的是什么接口"都说不清
        if (request == null || !request.hasRoute() || isBlank(request.requestId())) {
            return error(HttpStatus.BAD_REQUEST, UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
        }
        String userId = userContext.userId();

        // G4：用户级限速（计数放在共享存储里，多副本才同一口径，§2.3）
        boolean withinQuota;
        try {
            withinQuota = rateLimiter.tryAcquire(userId);
        } catch (RedisUserRateLimiter.UserRateLimitUnavailableException e) {
            // 计数存储不可用 = 算不出配额。不静默放行（等于把限速整条丢掉），也不退化成「本实例自己数」，
            // 如实报服务不可用——调用方看到的应当是 503，而不是「你太快了」。
            log.error("用户限速计数不可用，拒绝放行：userId={}", userId, e);
            return error(HttpStatus.SERVICE_UNAVAILABLE, UnifiedErrors.SERVICE_UNAVAILABLE);
        }
        if (!withinQuota) {
            return error(HttpStatus.TOO_MANY_REQUESTS, UnifiedErrors.RATE_LIMITED);
        }

        // G2′：按三元组解析注册行——查不到就是"这扇门没登记过"
        ApiDescriptor descriptor = apiRegistry
                .findByRoute(request.service(), request.httpMethod(), request.httpPath())
                .orElse(null);
        if (descriptor == null) {
            audit.deny("API_NOT_REGISTERED");
            return error(HttpStatus.NOT_FOUND, UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
        }
        if (!descriptor.enabled()) {
            audit.deny("API_DISABLED");
            return error(HttpStatus.NOT_FOUND, UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
        }

        // G3：写操作必须先拿到用户确认（§18.4.5 W1 / §19.9），一次性消费
        if (descriptor.kind().isWrite()
                && (isBlank(request.confirmId()) || !confirmStore.consume(request.confirmId(), userId))) {
            audit.deny("CONFIRM_REQUIRED");
            return error(HttpStatus.FORBIDDEN, UnifiedErrors.FORBIDDEN);
        }

        // G3：权限判定（唯一判定点，§4.8 节点 E）——比对的键是注册行 id，不是任何可读编码
        ApiCallDecision decision = authorizationService.decideApiCall(userId, descriptor.id(), request.skillCode());
        if (!decision.allowed()) {
            audit.deny(decision.reason());
            return error(HttpStatus.FORBIDDEN, UnifiedErrors.FORBIDDEN);
        }

        // G4：熔断（按接口隔离，不是按服务——一个接口拖垮整个服务的转发没有道理）
        CircuitBreakerRegistry.Breaker breaker = breakers.forKey(descriptor.route().toString());
        if (!breaker.allowRequest()) {
            audit.deny("CIRCUIT_OPEN");
            return error(HttpStatus.SERVICE_UNAVAILABLE, UnifiedErrors.SERVICE_UNAVAILABLE);
        }

        // G5：放行也记账（谁 / 哪个接口 / 什么时候）
        audit.allow();

        String requestId = request.requestId();
        String traceId = userContext.traceId() == null ? requestId : userContext.traceId();
        CallerInfo caller = new CallerInfo(userId, requestId, traceId, request.skillCode(), request.confirmId());
        ApiEnvelope<Map<String, Object>> envelope = new ApiEnvelope<>(caller, request.args());
        try {
            DownstreamResponse response = downstream.send(envelope, descriptor);
            breaker.recordSuccess();
            if (!response.isSuccess()) {
                return ResponseEntity.status(response.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response.body());
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(truncator.truncate(response.body()));
        } catch (DownstreamUnavailableException e) {
            breaker.recordFailure();
            audit.deny(e.isTimeout() ? "DOWNSTREAM_TIMEOUT" : "DOWNSTREAM_UNAVAILABLE");
            log.warn("接口服务不可用 route={} timeout={} message={}", routeLabel(request), e.isTimeout(), e.getMessage());
            return error(
                    e.isTimeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    UnifiedErrors.SERVICE_UNAVAILABLE);
        }
    }

    /** 审计与日志里的接口标识（只用来留痕，绝不参与路由或判定）。 */
    private static String routeLabel(ApiCallRequest request) {
        return request.httpMethod() + " " + request.httpPath();
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + message + "\"}");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}