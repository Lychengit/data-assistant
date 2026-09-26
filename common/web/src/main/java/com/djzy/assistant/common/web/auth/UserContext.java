package com.djzy.assistant.common.web.auth;

import java.util.Objects;

/**
 * 一次请求里的「当前用户」。
 *
 * <p>它只活在**这一次请求的线程**里（见 {@link UserContextHolder}），不落任何全局变量，
 * 请求一结束就清掉——所以不会出现「A 用户的身份被 B 用户读到」。
 *
 * @param userId      已验明的用户 id。全链路只透传这一个字段（§4.8）
 * @param bearerToken 原始登录令牌（{@code Authorization: Bearer } 之后的那一段）。
 *                    agent-service 调网关时要原样透传给网关（§20.1.6-5），所以顺手带着走；
 *                    它只在本次请求的线程内可见，不进日志、不进 URL
 * @param traceId     链路 id，用来把一次请求的日志串起来；取不到时为 null
 */
public record UserContext(String userId, String bearerToken, String traceId) {

    public UserContext {
        Objects.requireNonNull(userId, "userId");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
    }

    /** 只要用户 id 的上下文（不需要透传令牌时用）。 */
    public static UserContext of(String userId) {
        return new UserContext(userId, null, null);
    }

    public UserContext withToken(String token) {
        return new UserContext(userId, token, traceId);
    }

    public UserContext withTraceId(String trace) {
        return new UserContext(userId, bearerToken, trace);
    }
}
