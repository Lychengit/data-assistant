package com.djzy.assistant.common.identity;

import java.util.Objects;

/**
 * 已解析的可信用户身份（§4.8 会话层·身份解析）：全链路只透传 {@code userId}。
 *
 * <p>身份解析只发生在请求入口（agent-service / management-service）与网关 G1；
 * 下游服务（接口服务）**不重新解析身份**，直接使用网关下发的 {@code userId}（§20.1.1）。
 */
public record UserIdentity(String userId) {

    public UserIdentity {
        Objects.requireNonNull(userId, "userId");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
    }

    public static UserIdentity of(String userId) {
        return new UserIdentity(userId);
    }
}
