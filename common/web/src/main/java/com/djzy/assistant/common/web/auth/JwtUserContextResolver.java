package com.djzy.assistant.common.web.auth;

import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import com.djzy.assistant.common.web.RequestTracing;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * 「验登录令牌 + 查账号状态」的通用身份解析。
 *
 * <p>就两步：先从 {@code Authorization: Bearer xxx} 验出 userId，再直查一次账号状态；
 * 两步都过才算认下这个人（§4.8 / §19.4）。
 *
 * <p>为什么每次都查账号状态：令牌里**没有**状态位，状态是权威源里的事实。
 * 「令牌还没过期就放行」在停用 / 离职这个场景下就是事故。
 *
 * <p>不在这里读任何权限：身份与权限是两件事，权限判定只发生在网关 G3（§20.1.6-5）。
 */
public final class JwtUserContextResolver implements UserContextResolver {

    private final UserTokenVerifier tokenVerifier;
    private final UserStatusPort userStatusPort;

    public JwtUserContextResolver(UserTokenVerifier tokenVerifier, UserStatusPort userStatusPort) {
        this.tokenVerifier = tokenVerifier;
        this.userStatusPort = userStatusPort;
    }

    @Override
    public Optional<UserContext> resolve(HttpServletRequest request) {
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)).orElse(null);
        if (token == null) {
            return Optional.empty();
        }
        return tokenVerifier
                .verify(token)
                .filter(identity -> userStatusPort.isActive(identity.userId()))
                .map(identity -> new UserContext(identity.userId(), token, RequestTracing.traceId(request)));
    }

    /** 取 {@code Authorization: Bearer xxx} 里令牌本体；格式不对就是「没有」。 */
    public static Optional<String> bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return Optional.empty();
        }
        String token = value.substring("Bearer ".length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
