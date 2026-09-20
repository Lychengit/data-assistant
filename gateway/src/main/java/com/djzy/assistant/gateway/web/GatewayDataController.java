package com.djzy.assistant.gateway.web;

import com.djzy.assistant.common.api.ApiCallRequest;
import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.ApiRegistry;
import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.confirm.ConfirmStore;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import com.djzy.assistant.common.permission.ApiCallDecision;
import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.permission.PermissionAuditEntry;
import com.djzy.assistant.common.permission.PermissionAuditWriter;
import com.djzy.assistant.common.permission.PermissionDecision;
import com.djzy.assistant.common.web.RequestAttributes;
import com.djzy.assistant.gateway.core.CircuitBreakerRegistry;
import com.djzy.assistant.gateway.core.DownstreamClient;
import com.djzy.assistant.gateway.core.DownstreamResponse;
import com.djzy.assistant.gateway.core.DownstreamUnavailableException;
import com.djzy.assistant.gateway.core.ResponseTruncator;
import com.djzy.assistant.gateway.core.UserRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关数据调用入口：G1 身份 → G4 限额 → G2′ 路由解析 → G3 判定 → G2 转发 → G5 记账（§18.4.2）。
 *
 * <p>关键点一：不接受调用方自带的 userId / 角色（§20.1.6-5）——身份只从用户凭证推导。
 *
 * <p>关键点二：**接口身份是三元组**「服务 + 方法 + 路径」，不是某个接口编码。G2′ 用三元组精确查
 * 注册行，查到行才谈得上"这个用户有没有这把钥匙"（G3 拿 {@code sys_api.id} 去比 {@code role_api}）。
 * 查不到行 = 这扇门没登记过；登记了但停用 = 也不放行。两种都对外返回同一措辞，差异只进审计。
 *
 * <p>关键点三：转发地址来自**注册行**（{@link com.djzy.assistant.gateway.core.ServiceRouter}），
 * 不来自请求体——见那里的说明。
 */
@RestController
public class GatewayDataController {

    private static final Logger log = LoggerFactory.getLogger(GatewayDataController.class);

    private final UserTokenVerifier tokenVerifier;
    private final UserStatusPort userStatusPort;
    private final ApiRegistry apiRegistry;
    private final AuthorizationService authorizationService;
    private final PermissionAuditWriter auditWriter;
    private final ConfirmStore confirmStore;
    private final UserRateLimiter rateLimiter;
    private final CircuitBreakerRegistry breakers;
    private final DownstreamClient downstream;
    private final ResponseTruncator truncator;

    public GatewayDataController(
            UserTokenVerifier tokenVerifier,
            UserStatusPort userStatusPort,
            ApiRegistry apiRegistry,
            AuthorizationService authorizationService,
            PermissionAuditWriter auditWriter,
            ConfirmStore confirmStore,
            UserRateLimiter rateLimiter,
            CircuitBreakerRegistry breakers,
            DownstreamClient downstream,
            ResponseTruncator truncator) {
        this.tokenVerifier = tokenVerifier;
        this.userStatusPort = userStatusPort;
        this.apiRegistry = apiRegistry;
        this.authorizationService = authorizationService;
        this.auditWriter = auditWriter;
        this.confirmStore = confirmStore;
        this.rateLimiter = rateLimiter;
        this.breakers = breakers;
        this.downstream = downstream;
        this.truncator = truncator;
    }

    @PostMapping(path = "/v1/gateway/api-call", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> apiCall(
            @RequestBody(required = false) ApiCallRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceHeader,
            @RequestAttribute(value = RequestAttributes.CALLER_KEY_ID, required = false) String callerKeyId,
            HttpServletRequest httpRequest) {
        if (request == null || !request.hasRoute() || isBlank(request.requestId())) {
            return error(HttpStatus.BAD_REQUEST, UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
        }
        String requestId = request.requestId();
        String traceId = isBlank(traceHeader) ? UUID.randomUUID().toString() : traceHeader;
        String routeLabel = routeLabel(request);

        // G1：身份解析（JWT -> userId）与账号状态（停用 / 离职立即失效，§19.4）
        String userId = tokenVerifier
                .verifyAuthorizationHeader(authorization)
                .map(UserIdentity::userId)
                .orElse(null);
        if (userId == null || !userStatusPort.isActive(userId)) {
            log.warn("网关拒绝：身份不可用 path={} peer={} caller={}",
                    httpRequest.getRequestURI(), httpRequest.getRemoteAddr(), callerKeyId);
            return error(HttpStatus.UNAUTHORIZED, UnifiedErrors.UNAUTHORIZED);
        }

        // G4：用户级限速
        if (!rateLimiter.tryAcquire(userId)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, UnifiedErrors.RATE_LIMITED);
        }

        // G2′：按三元组解析注册行——查不到就是"这扇门没登记过"
        ApiDescriptor descriptor = apiRegistry
                .findByRoute(request.service(), request.httpMethod(), request.httpPath())
                .orElse(null);
        if (descriptor == null) {
            audit(requestId, traceId, userId, routeLabel, PermissionDecision.DENY, "API_NOT_REGISTERED");
            return error(HttpStatus.NOT_FOUND, UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
        }
        if (!descriptor.enabled()) {
            audit(requestId, traceId, userId, routeLabel, PermissionDecision.DENY, "API_DISABLED");
            return error(HttpStatus.NOT_FOUND, UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
        }

        // G3：写操作必须先拿到用户确认（§18.4.5 W1 / §19.9），一次性消费
        if (descriptor.kind().isWrite()
                && (isBlank(request.confirmId()) || !confirmStore.consume(request.confirmId(), userId))) {
            audit(requestId, traceId, userId, routeLabel, PermissionDecision.DENY, "CONFIRM_REQUIRED");
            return error(HttpStatus.FORBIDDEN, UnifiedErrors.FORBIDDEN);
        }

        // G3：权限判定（唯一判定点，§4.8 节点 E）——比对的键是注册行 id，不是任何可读编码
        ApiCallDecision decision = authorizationService.decideApiCall(userId, descriptor.id(), request.skillCode());
        if (!decision.allowed()) {
            audit(requestId, traceId, userId, routeLabel, PermissionDecision.DENY, decision.reason());
            return error(HttpStatus.FORBIDDEN, UnifiedErrors.FORBIDDEN);
        }

        // G4：熔断（按接口隔离，不是按服务——一个接口拖垮整个服务的转发没有道理）
        CircuitBreakerRegistry.Breaker breaker = breakers.forKey(descriptor.route().toString());
        if (!breaker.allowRequest()) {
            audit(requestId, traceId, userId, routeLabel, PermissionDecision.DENY, "CIRCUIT_OPEN");
            return error(HttpStatus.SERVICE_UNAVAILABLE, UnifiedErrors.SERVICE_UNAVAILABLE);
        }

        // G5：放行也记账（谁 / 哪个接口 / 什么时候）
        audit(requestId, traceId, userId, routeLabel, PermissionDecision.ALLOW, null);

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
            audit(
                    requestId,
                    traceId,
                    userId,
                    routeLabel,
                    PermissionDecision.DENY,
                    e.isTimeout() ? "DOWNSTREAM_TIMEOUT" : "DOWNSTREAM_UNAVAILABLE");
            log.warn("接口服务不可用 route={} timeout={} message={}", routeLabel, e.isTimeout(), e.getMessage());
            return error(
                    e.isTimeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    UnifiedErrors.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 审计里的接口标识。
     *
     * <p>用请求里的三元组而不是注册行的工具名：拒绝的请求（未注册 / 停用）根本没有注册行可读，
     * 而恰恰是这些请求最需要留痕。这串值来自请求体，只进日志与审计、绝不参与路由或判定。
     */
    private static String routeLabel(ApiCallRequest request) {
        return request.httpMethod() + " " + request.httpPath();
    }

    /** G5 记账：放行与拒绝都要写（§0.3-3 直写 PG、一条不漏）。 */
    private void audit(
            String requestId,
            String traceId,
            String userId,
            String routeLabel,
            PermissionDecision decision,
            String reason) {
        try {
            auditWriter.write(new PermissionAuditEntry(
                    requestId == null ? traceId : requestId,
                    userId,
                    safeRoles(userId),
                    routeLabel,
                    decision,
                    reason,
                    null,
                    Instant.now()));
        } catch (RuntimeException e) {
            log.error("权限审计写入失败（§0.3-3 必须告警）：userId={} route={} decision={}",
                    userId, routeLabel, decision, e);
        }
    }

    private List<String> safeRoles(String userId) {
        try {
            return authorizationService.rolesOf(userId);
        } catch (RuntimeException e) {
            return List.of();
        }
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