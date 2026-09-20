package com.djzy.assistant.iface.doctor.audit;

import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.web.RequestAttributes;
import com.djzy.assistant.iface.doctor.catalog.RegisteredRoute;
import com.djzy.assistant.iface.doctor.catalog.RegisteredRouteCatalog;
import com.djzy.assistant.iface.doctor.service.WriteConfirmationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 已注册接口的横切规则：写操作确认门 + I6 数据访问审计（§18.4.5 I2 / I6）。
 *
 * <p>为什么要切面：这两件事对**每个**接口都一样。让接口自己写，一是重复，二是漏写不会有任何提示——
 * 而"审计少一条"和"写操作没确认"都是事后无法从结果里看出来的错。做成切面后，新增接口只要守住
 * 两个约定（入参 {@code ApiEnvelope<T>}、返回 {@link com.djzy.assistant.common.api.ApiResult}），
 * 这两件事自动生效。
 *
 * <p>切面只做"框架层面"的判断与记账：谁、调了哪个端点、成功/失败、返回多少行。
 * 业务语义（本次实际用了什么过滤条件）它看不到——那要接口自己往审计上下文里补，
 * 见 {@link DoctorAccessAuditor} 的说明。
 */
@Aspect
@Component
public class RegisteredApiAspect {

    private static final Logger log = LoggerFactory.getLogger(RegisteredApiAspect.class);

    private final RegisteredRouteCatalog catalog;
    private final DoctorAccessAuditor auditor;

    public RegisteredApiAspect(RegisteredRouteCatalog catalog, DoctorAccessAuditor auditor) {
        this.catalog = catalog;
        this.auditor = auditor;
    }

    @Around("within(com.djzy.assistant.iface.doctor.web..*) && ("
            + "@annotation(org.springframework.web.bind.annotation.PostMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.GetMapping))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return joinPoint.proceed();
        }
        String httpMethod = request.getMethod();
        // 用匹配到的路径模板而不是原始 URI：模板才是注册表里的那一份，原始 URI 会带多余斜杠之类的东西
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String httpPath = pattern == null ? request.getRequestURI() : String.valueOf(pattern);

        RegisteredRoute route = catalog.find(httpPath).orElse(null);
        CallerInfo caller = callerOf(joinPoint.getArgs());
        String callerKeyId = (String) request.getAttribute(RequestAttributes.CALLER_KEY_ID);
        if (route == null || caller == null) {
            log.error("接口未按约定声明（入参须为 ApiEnvelope<T> 且路径已注册），本次未记账：{} {} 有路由={} 有身份={}",
                    httpMethod, httpPath, route != null, caller != null);
            return joinPoint.proceed();
        }

        // I2：写操作必须带确认凭据。凭据的权威校验在网关（一次性消费），这里只做存在性检查——
        // 纵深防御的意义在于"网关那条路走错了"时不至于直接把写操作放出去。
        if (route.kind().isWrite() && isBlank(caller.confirmId())) {
            auditor.deny(caller, callerKeyId, httpMethod, httpPath, "CONFIRM_MISSING");
            throw new WriteConfirmationRequiredException(httpPath);
        }

        try {
            Object result = joinPoint.proceed();
            auditor.allow(caller, callerKeyId, httpMethod, httpPath, result);
            return result;
        } catch (Throwable e) {
            auditor.error(caller, callerKeyId, httpMethod, httpPath, e.getClass().getSimpleName());
            throw e;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /** 从方法实参里找信封；找不到说明这个接口没守约定。 */
    private static CallerInfo callerOf(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof ApiEnvelope<?> envelope) {
                return envelope.caller();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}