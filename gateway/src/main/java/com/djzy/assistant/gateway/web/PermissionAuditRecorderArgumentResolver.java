package com.djzy.assistant.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link PermissionAuditRecorder} 注进控制器参数。
 *
 * <p>从请求属性里取（没有就建一个）——所以同一个请求里，控制器和切面拿到的是同一个盒子。
 * 用参数注入而不是 ThreadLocal：控制器方法签名上一眼就能看出「这个方法会记账」。
 */
public final class PermissionAuditRecorderArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PermissionAuditRecorder.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new IllegalStateException("拿不到 HttpServletRequest，无法创建权限审计记录器");
        }
        return PermissionAuditRecorder.of(request);
    }
}
