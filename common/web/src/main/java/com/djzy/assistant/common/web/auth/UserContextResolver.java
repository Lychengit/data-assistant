package com.djzy.assistant.common.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * 「这次请求是谁」的解析口子。
 *
 * <p>每个服务的验证方式不一样，所以只留一个口子，具体怎么验由各服务自己的 bean 决定。
 * 拦截器（{@link UserContextInterceptor}）只认这个口子，不认识 JWT、也不认识任何具体格式。
 *
 * <p>返回 {@link Optional#empty()} 一律按 401 处理；**不区分失败原因**（没令牌 / 令牌坏了 /
 * 账号停了）是对外的基本要求，防探测（§20.1.2）。
 */
@FunctionalInterface
public interface UserContextResolver {

    Optional<UserContext> resolve(HttpServletRequest request);
}
