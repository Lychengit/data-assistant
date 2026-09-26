package com.djzy.assistant.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 签名串 / 待验签请求（§20.1.3，用 {@code \n} 逐行拼接）。
 *
 * <pre>
 * v1
 * {HTTP 方法大写}
 * {请求路径（含 base path，不含 query）}
 * {query 串：参数名按字典序升序拼 k=v&amp;k2=v2；无 query 则空行}
 * {X-Timestamp}
 * {X-Nonce}
 * {SHA-256(请求体) 的 hex 小写}
 * </pre>
 *
 * <p>请求体摘要必须在签名串里：网关下发的可信身份与业务入参（{@code caller.userId / requestId} 与
 * {@code args}）全在请求体里，改一个字节验签就失败——这是本方案的核心价值。
 *
 * <p>**注意签名保护的是什么**：是「身份和入参没被改」，**不是**「数据范围没被改」。
 * 数据范围既不在网关计算、也不下发（§19.1 / ADR-37），所以它压根不在请求体里，也就无所谓被篡改。
 */
public record CanonicalRequest(
        String method, String path, Map<String, List<String>> queryParams, long timestampSeconds, String nonce, String body) {

    public CanonicalRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        Objects.requireNonNull(nonce, "nonce");
    }

    public static CanonicalRequest of(String method, String path, Map<String, List<String>> query, String timestamp, String nonce, String body) {
        return new CanonicalRequest(method, path, query, Long.parseLong(timestamp), nonce, body);
    }

    /** 归一化 query：参数名按字典序升序，重复参数按给定顺序排列。 */
    public String normalizedQuery() {
        if (queryParams.isEmpty()) {
            return "";
        }
        Map<String, List<String>> sorted = new TreeMap<>(queryParams);
        List<String> parts = new ArrayList<>();
        sorted.forEach((k, values) -> {
            if (values == null || values.isEmpty()) {
                parts.add(k + "=");
            } else {
                values.forEach(v -> parts.add(k + "=" + (v == null ? "" : v)));
            }
        });
        return String.join("&", parts);
    }

    public String canonicalString() {
        return String.join(
                "\n",
                SignatureHeaders.ALGORITHM_VERSION,
                method.toUpperCase(),
                path,
                normalizedQuery(),
                String.valueOf(timestampSeconds),
                nonce,
                Hmac.sha256Hex(body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
