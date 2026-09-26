package com.djzy.assistant.gateway.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 网关 MVC 装配：让控制器能声明 {@link PermissionAuditRecorder} 参数。 */
@Configuration
public class PermissionAuditWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PermissionAuditRecorderArgumentResolver());
    }
}
