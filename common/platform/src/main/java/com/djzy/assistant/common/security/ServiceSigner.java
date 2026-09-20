package com.djzy.assistant.common.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间签名唯一实现（§20.1.3）：业务代码不得自行拼接签名串。
 *
 * <p>覆盖范围：{@code agent-service → 网关}、{@code management-service → 网关}、{@code 网关 → 接口服务}、
 * {@code 网关 → 技能执行服务}、{@code 技能执行服务 → 沙箱}、{@code 沙箱 → 网关}（§20.1.2）。
 */
public final class ServiceSigner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 16;

    private ServiceSigner() {}

    public static String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static Map<String, String> sign(
            ServiceCredential credential,
            String method,
            String path,
            Map<String, List<String>> queryParams,
            String body) {
        long timestamp = System.currentTimeMillis() / 1000L;
        return sign(credential, method, path, queryParams, body, timestamp, newNonce());
    }

    public static Map<String, String> sign(
            ServiceCredential credential,
            String method,
            String path,
            Map<String, List<String>> queryParams,
            String body,
            long timestampSeconds,
            String nonce) {
        CanonicalRequest request = new CanonicalRequest(method, path, queryParams, timestampSeconds, nonce, body);
        String signature = Hmac.sign(credential.sharedSecret(), request.canonicalString());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(SignatureHeaders.API_KEY, credential.keyId());
        headers.put(SignatureHeaders.TIMESTAMP, String.valueOf(timestampSeconds));
        headers.put(SignatureHeaders.NONCE, nonce);
        headers.put(SignatureHeaders.SIGNATURE, signature);
        return Map.copyOf(headers);
    }

    static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
