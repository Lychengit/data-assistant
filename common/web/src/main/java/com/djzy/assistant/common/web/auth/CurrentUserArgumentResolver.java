package com.djzy.assistant.common.web.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link CurrentUser} 标注的参数填上：从当前线程的 {@link UserContextHolder} 里取。
 *
 * <p>拿不到上下文就直接报错，而不是塞个 null 进去——控制器拿到 null 的用户 id，
 * 只会变成更难查的问题。
 */
public final class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(CurrentUser.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return type == UserContext.class || type == String.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        UserContext context = UserContextHolder.require();
        return parameter.getParameterType() == String.class ? context.userId() : context;
    }
}
