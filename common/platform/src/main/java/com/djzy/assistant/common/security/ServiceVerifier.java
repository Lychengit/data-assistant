package com.djzy.assistant.common.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务间验签唯一实现（§20.1.4），在网关 G1 / 接口服务 I1 执行。
 *
 * <p>校验步骤：① keyId 查密钥 → ② 时间窗 ±300s → ③ nonce 一次性 → ④ 重算签名 → ⑤ 常量时间比较。
 * 任何一步失败都返回统一 401 措辞并写审计 + 告警。
 */
public final class ServiceVerifier {

    /** 时间窗：±300s（§20.1.3）。 */
    public static final Duration TIMESTAMP_WINDOW = Duration.ofSeconds(300);

    /** nonce TTL：略大于时间窗（§20.1.4-3）。 */
    public static final Duration NONCE_TTL = Duration.ofSeconds(600);

    private final SecretResolver secretResolver;
    private final NonceStore nonceStore;

    public ServiceVerifier(SecretResolver secretResolver, NonceStore nonceStore) {
        this.secretResolver = secretResolver;
        this.nonceStore = nonceStore;
    }

    /**
     * @param headers 请求头（大小写不敏感查找）
     * @param nowEpochSeconds 当前时间（注入以便测试）
     */
    public VerificationResult verify(
            Map<String, String> headers,
            String method,
            String path,
            Map<String, List<String>> queryParams,
            String body,
            long nowEpochSeconds) {
        String keyId = header(headers, SignatureHeaders.API_KEY);
        String timestamp = header(headers, SignatureHeaders.TIMESTAMP);
        String nonce = header(headers, SignatureHeaders.NONCE);
        String signature = header(headers, SignatureHeaders.SIGNATURE);
        if (isBlank(keyId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return VerificationResult.fail(SecurityFailure.MISSING_HEADERS, keyId);
        }
        if (!signature.startsWith(SignatureHeaders.SIGNATURE_PREFIX)) {
            return VerificationResult.fail(SecurityFailure.INVALID_SIGNATURE_FORMAT, keyId);
        }
        Optional<String> secret = secretResolver.secretFor(keyId);
        if (secret.isEmpty()) {
            return VerificationResult.fail(SecurityFailure.UNKNOWN_KEY, keyId);
        }
        long requestTimestamp;
        try {
            requestTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return VerificationResult.fail(SecurityFailure.TIMESTAMP_OUT_OF_WINDOW, keyId);
        }
        if (Math.abs(nowEpochSeconds - requestTimestamp) > TIMESTAMP_WINDOW.toSeconds()) {
            return VerificationResult.fail(SecurityFailure.TIMESTAMP_OUT_OF_WINDOW, keyId);
        }
        if (!nonceStore.tryConsume(keyId, nonce, NONCE_TTL)) {
            return VerificationResult.fail(SecurityFailure.NONCE_REPLAYED, keyId);
        }
        CanonicalRequest canonical = new CanonicalRequest(method, path, queryParams, requestTimestamp, nonce, body);
        String expected = Hmac.sign(secret.get(), canonical.canonicalString());
        if (!Hmac.constantTimeEquals(expected, signature)) {
            return VerificationResult.fail(SecurityFailure.SIGNATURE_MISMATCH, keyId);
        }
        return VerificationResult.ok(keyId);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
