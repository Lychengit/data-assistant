package com.djzy.assistant.common.identity;

import com.djzy.assistant.common.security.SecretResolver;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 登录令牌服务：签发（management-service M1）+ 校验（网关 G1），共用同一份 HS256 实现。
 *
 * <p>有效期上限 15 分钟（§19.4），构造时即拒绝更长的 TTL——「签发一次管一天」是明确禁止的形态。
 */
public final class JwtTokenService implements UserTokenIssuer, UserTokenVerifier {

    /** 令牌有效期硬上限（§19.4）。 */
    public static final Duration MAX_TTL = Duration.ofMinutes(15);

    private final SecretResolver secretResolver;
    private final String tokenKeyId;
    private final Duration ttl;

    public JwtTokenService(SecretResolver secretResolver, String tokenKeyId, Duration ttl) {
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.tokenKeyId = Objects.requireNonNull(tokenKeyId, "tokenKeyId");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("JWT 有效期必须在 (0, 15 分钟] 之内（§19.4）");
        }
        this.ttl = ttl;
    }

    @Override
    public String issue(String userId, Map<String, Object> extraClaims) {
        return HmacJwt.issue(requiredSecret(), userId, ttl, extraClaims);
    }

    @Override
    public Optional<UserIdentity> verify(String token) {
        return secretResolver
                .secretFor(tokenKeyId)
                .flatMap(secret -> HmacJwt.verify(secret, token))
                .map(claims -> UserIdentity.of(claims.subject()));
    }

    private String requiredSecret() {
        return secretResolver
                .secretFor(tokenKeyId)
                .orElseThrow(() -> new IllegalStateException("缺少 JWT 签名密钥 " + tokenKeyId + "（§20.1.5）"));
    }
}
