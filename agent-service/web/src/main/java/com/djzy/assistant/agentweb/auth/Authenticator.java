package com.djzy.assistant.agentweb.auth;

import com.djzy.assistant.agentweb.web.ApiException;
import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import java.util.Optional;

/**
 * 身份解析（§4.8 会话层 / §19.4）：JWT 只证明身份，**账号状态每次直查 PG**。
 *
 * <p>停用 / 离职必须立即失效，所以这里不接受「令牌还没过期就放行」——令牌里没有状态位，
 * 状态是权威源里的事实。
 */
public final class Authenticator {

    private final UserTokenVerifier tokenVerifier;
    private final UserStatusPort userStatusPort;

    public Authenticator(UserTokenVerifier tokenVerifier, UserStatusPort userStatusPort) {
        this.tokenVerifier = tokenVerifier;
        this.userStatusPort = userStatusPort;
    }

    /** @return 已验明的 userId；令牌无效或账号非 active 一律 401（不区分原因，防探测） */
    public String requireUser(String authorizationHeader) {
        String token = requireToken(authorizationHeader);
        String userId = tokenVerifier
                .verifyAuthorizationHeader(authorizationHeader)
                .map(UserIdentity::userId)
                .orElseThrow(ApiException::unauthorized);
        if (!userStatusPort.isActive(userId)) {
            throw ApiException.unauthorized();
        }
        return userId;
    }

    /** 取原始令牌（调用网关时**透传**，agent 侧不从令牌里读权限，§20.1.6-5）。 */
    public String requireToken(String authorizationHeader) {
        return extract(authorizationHeader).orElseThrow(ApiException::unauthorized);
    }

    public static Optional<String> extract(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        String value = authorizationHeader.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }
        return Optional.empty();
    }
}
