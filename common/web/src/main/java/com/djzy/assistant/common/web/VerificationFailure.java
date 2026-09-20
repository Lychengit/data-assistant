package com.djzy.assistant.common.web;

import com.djzy.assistant.common.security.SecurityFailure;
import java.time.Instant;
import java.util.Optional;

/**
 * 验签失败记录（§20.1.4-7）：签名失败时 requestId 本身不可信，
 * 因此必须同时记原始对端信息（IP + 服务名 + keyId + 时间戳 + 原因）。
 */
public record VerificationFailure(
        String keyId,
        SecurityFailure reason,
        String path,
        String peer,
        String timestampHeader,
        String serviceName,
        Instant occurredAt) {

    public Optional<SecurityFailure> reasonIfPresent() {
        return Optional.ofNullable(reason);
    }
}
