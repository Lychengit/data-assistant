package com.djzy.assistant.gateway.core;

import java.nio.charset.StandardCharsets;

/**
 * 响应过大截断（§18.4.2 G4）：超限时不返回半截 JSON，而是返回一个合法的截断信封——
 * 半截 JSON 会让下游解析出莫名其妙的错误，比明确截断更糟。
 */
public final class ResponseTruncator {

    private final int maxBytes;

    public ResponseTruncator(int maxBytes) {
        this.maxBytes = Math.max(1024, maxBytes);
    }

    public String truncate(String body) {
        if (body == null) {
            return "";
        }
        int size = body.getBytes(StandardCharsets.UTF_8).length;
        if (size <= maxBytes) {
            return body;
        }
        return "{\"truncated\":true,\"originalBytes\":" + size
                + ",\"maxBytes\":" + maxBytes
                + ",\"note\":\"响应过大已截断，请缩小查询范围\"}";
    }

    public boolean isTruncated(String body) {
        return body != null && body.getBytes(StandardCharsets.UTF_8).length > maxBytes;
    }
}
