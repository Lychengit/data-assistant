package com.djzy.assistant.agentweb;

import com.djzy.assistant.common.identity.JwtTokenService;
import com.djzy.assistant.common.identity.UserTokenIssuer;
import com.djzy.assistant.common.security.StaticSecretResolver;
import java.time.Duration;
import java.util.Map;

/** 测试基座：签发测试令牌用的密钥与账号种子，与 {@code schema-h2.sql} 对齐。 */
public final class AgentWebTestSupport {

    public static final String JWT_SECRET = "agent-web-test-secret";
    public static final String TOKEN_KEY_ID = "login-token";
    public static final String ALICE = "alice";
    public static final String BOB = "bob";
    public static final String GONE = "gone";

    private static final UserTokenIssuer ISSUER = new JwtTokenService(
            StaticSecretResolver.of(Map.of(TOKEN_KEY_ID, JWT_SECRET)), TOKEN_KEY_ID, Duration.ofMinutes(15));

    private AgentWebTestSupport() {}

    /** 与管理后台同一密钥签发的访问令牌（§19.4）。 */
    public static String token(String userId) {
        return ISSUER.issue(userId, Map.of("name", userId));
    }

    public static String bearer(String userId) {
        return "Bearer " + token(userId);
    }
}
