package com.djzy.assistant.common.identity;

import com.djzy.assistant.common.security.Hmac;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * HS256 JWT 唯一实现（§19.4）：不引入第二份令牌编解码，也不接受 {@code alg=none}。
 *
 * <p>拒绝清单：算法非 HS256 / 缺 {@code exp} / 已过期 / {@code iat} 超前 / 签名不匹配 → 一律
 * {@link Optional#empty()}，由调用方按 401 统一处理（§20.1.2 不区分失败原因，防探测）。
 */
public final class HmacJwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String ALGORITHM = "HS256";
    /** 允许的时钟偏差（服务间时钟不同步的余量）。 */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Set<String> RESERVED = Set.of("sub", "iat", "exp", "jti");

    private HmacJwt() {}

    public static String issue(String secret, String subject, Duration ttl, Map<String, Object> extraClaims) {
        return issue(secret, subject, ttl, extraClaims, Instant.now());
    }

    public static String issue(
            String secret, String subject, Duration ttl, Map<String, Object> extraClaims, Instant now) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("缺少 JWT 签名密钥（§20.1.5 缺密钥拒绝启动）");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject 不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subject);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plus(ttl).getEpochSecond());
        payload.put("jti", UUID.randomUUID().toString());
        if (extraClaims != null) {
            extraClaims.forEach((k, v) -> {
                if (k != null && !RESERVED.contains(k)) {
                    payload.put(k, v);
                }
            });
        }
        String header = encode(Map.of("alg", ALGORITHM, "typ", "JWT"));
        String body = encode(payload);
        String signingInput = header + "." + body;
        return signingInput + "." + URL_ENCODER.encodeToString(Hmac.hmacSha256(secret, signingInput));
    }

    public static Optional<UserTokenClaims> verify(String secret, String token) {
        return verify(secret, token, Instant.now());
    }

    public static Optional<UserTokenClaims> verify(String secret, String token, Instant now) {
        if (secret == null || secret.isBlank() || token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];
        String expected = URL_ENCODER.encodeToString(Hmac.hmacSha256(secret, signingInput));
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> header = decode(parts[0]);
        if (header.isEmpty() || !ALGORITHM.equals(header.get().get("alg"))) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> payload = decode(parts[1]);
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> claims = payload.get();
        String subject = asString(claims.get("sub"));
        Long issuedAt = asEpochSecond(claims.get("iat"));
        Long expiresAt = asEpochSecond(claims.get("exp"));
        if (subject == null || issuedAt == null || expiresAt == null) {
            return Optional.empty();
        }
        Instant issued = Instant.ofEpochSecond(issuedAt);
        Instant expires = Instant.ofEpochSecond(expiresAt);
        if (expires.isBefore(now.minus(CLOCK_SKEW))) {
            return Optional.empty();
        }
        if (issued.isAfter(now.plus(CLOCK_SKEW))) {
            return Optional.empty();
        }
        Map<String, Object> extra = new LinkedHashMap<>(claims);
        RESERVED.forEach(extra::remove);
        return Optional.of(new UserTokenClaims(subject, issued, expires, asString(claims.get("jti")), extra));
    }

    private static String encode(Map<String, Object> json) {
        try {
            return URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(json));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 编码失败", e);
        }
    }

    private static Optional<Map<String, Object>> decode(String part) {
        try {
            return Optional.of(MAPPER.readValue(URL_DECODER.decode(part), MAP_TYPE));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long asEpochSecond(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
