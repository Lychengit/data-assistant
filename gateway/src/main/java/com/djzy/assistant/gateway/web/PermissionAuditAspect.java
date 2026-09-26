package com.djzy.assistant.gateway.web;

import com.djzy.assistant.common.api.ApiCallRequest;
import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.permission.PermissionAuditEntry;
import com.djzy.assistant.common.permission.PermissionAuditWriter;
import com.djzy.assistant.common.web.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 权限审计切面：把 {@link PermissionAudited} 方法里记下的结论统一落审计
 * （§0.3-3：放行与拒绝都写、一条不漏）。
 *
 * <p>落在 {@code finally} 里——不管是正常返回还是抛异常，只要控制器留下了结论就一定落库。
 *
 * <p>两个刻意的取舍：
 * <ul>
 *   <li>审计写在**方法返回之后**，所以「放行」这条会比下游调用晚一点落库；
 *       内容与顺序都不变，只是落库时刻推后（原来在转发之前写）。</li>
 *   <li>审计写失败**不影响业务返回**，只告警——记账挂掉不该把用户请求也带挂。</li>
 * </ul>
 */
@Aspect
@Component
public class PermissionAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuditAspect.class);

    private final PermissionAuditWriter auditWriter;
    private final AuthorizationService authorizationService;

    public PermissionAuditAspect(PermissionAuditWriter auditWriter, AuthorizationService authorizationService) {
        this.auditWriter = auditWriter;
        this.authorizationService = authorizationService;
    }

    @Around("@annotation(com.djzy.assistant.gateway.web.PermissionAudited)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } finally {
            flush(joinPoint.getArgs());
        }
    }

    /** 把这次调用记下的结论逐条落库。 */
    private void flush(Object[] args) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        PermissionAuditRecorder recorder = PermissionAuditRecorder.find(request);
        if (recorder == null) {
            return;
        }
        ApiCallRequest call = firstOf(args, ApiCallRequest.class);
        UserContext user = firstOf(args, UserContext.class);
        String userId = user == null ? null : user.userId();
        String requestId = call == null ? null : call.requestId();
        // 链路 id 由拦截器保证非空；兜底用 requestId，别再生成一个（同一个请求要能串起来）
        String traceId = user == null || user.traceId() == null ? requestId : user.traceId();
        String routeLabel = routeLabel(call);
        for (PermissionAuditRecorder.Decision decision : recorder.decisions()) {
            write(requestId, traceId, userId, routeLabel, decision);
        }
    }

    /**
     * 审计里的接口标识。
     *
     * <p>用请求里的三元组而不是注册行的工具名：拒绝的请求（未注册 / 停用）根本没有注册行可读，
     * 而恰恰是这些请求最需要留痕。这串值来自请求体，只进日志与审计、绝不参与路由或判定。
     */
    private static String routeLabel(ApiCallRequest call) {
        return call == null ? null : call.httpMethod() + " " + call.httpPath();
    }

    private void write(
            String requestId,
            String traceId,
            String userId,
            String routeLabel,
            PermissionAuditRecorder.Decision decision) {
        try {
            auditWriter.write(new PermissionAuditEntry(
                    requestId == null ? traceId : requestId,
                    userId,
                    safeRoles(userId),
                    routeLabel,
                    decision.decision(),
                    decision.reason(),
                    null,
                    Instant.now()));
        } catch (RuntimeException e) {
            log.error(
                    "权限审计写入失败（§0.3-3 必须告警）：userId={} route={} decision={}",
                    userId,
                    routeLabel,
                    decision.decision(),
                    e);
        }
    }

    /** 角色只用来给审计留个快照；查不到不影响这条审计写不写（拒绝的请求本来就可能查不到人）。 */
    private List<String> safeRoles(String userId) {
        try {
            return authorizationService.rolesOf(userId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static <T> T firstOf(Object[] args, Class<T> type) {
        for (Object arg : args) {
            if (type.isInstance(arg)) {
                return type.cast(arg);
            }
        }
        return null;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
