package com.djzy.assistant.common.web.auth;

import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把「身份拦截器 + {@link CurrentUser} 参数解析器」一次装好。
 *
 * <p>每个服务在自己的配置类里 new 一个，并显式告诉它管哪些路径——
 * 各服务的开放范围不一样，所以路径**不给默认值**，避免不小心把不该管的路径也管了。
 */
public final class UserContextWebConfigurer implements WebMvcConfigurer {

    private final UserContextResolver resolver;
    private final List<String> pathPatterns;
    private final List<String> excludePathPatterns;

    public UserContextWebConfigurer(UserContextResolver resolver, List<String> pathPatterns) {
        this(resolver, pathPatterns, List.of());
    }

    public UserContextWebConfigurer(
            UserContextResolver resolver, List<String> pathPatterns, List<String> excludePathPatterns) {
        this.resolver = resolver;
        this.pathPatterns = List.copyOf(pathPatterns);
        this.excludePathPatterns = List.copyOf(excludePathPatterns);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        InterceptorRegistration registration = registry.addInterceptor(new UserContextInterceptor(resolver));
        registration.addPathPatterns(pathPatterns.toArray(String[]::new));
        if (!excludePathPatterns.isEmpty()) {
            registration.excludePathPatterns(excludePathPatterns.toArray(String[]::new));
        }
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
