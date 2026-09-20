package com.djzy.assistant.common.identity;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 登录令牌声明（§19.4）：无状态登录态证明身份，服务端不存 session。
 *
 * @param subject 用户 id（{@code sub}）
 * @param issuedAt 签发时间（{@code iat}）
 * @param expiresAt 过期时间（{@code exp}，有效期 ≤15 分钟）
 * @param tokenId 令牌 id（{@code jti}）
 * @param claims 其余声明（保留字段之外的扩展）
 */
public record UserTokenClaims(
        String subject, Instant issuedAt, Instant expiresAt, String tokenId, Map<String, Object> claims) {

    public UserTokenClaims {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }
}
