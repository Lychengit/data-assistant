package com.djzy.assistant.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 中立快照：框架状态导出为可持久化负载（落 AgentStateStore → PG，§19.5）。
 *
 * <p>负载对平台是不透明的（opaque），只有产出它的运行时实现能解释；
 * 因此 {@code runtimeId + runtimeVersion} 必须参与恢复校验（§19.14），不一致即拒绝恢复。
 */
public record Snapshot(
        String runtimeId,
        String runtimeVersion,
        String sessionId,
        long createdAtEpochMs,
        Map<String, Object> payload) {

    public Snapshot {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        payload = AgentEvent.immutablePayload(payload);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runtimeId", runtimeId);
        m.put("runtimeVersion", runtimeVersion);
        m.put("sessionId", sessionId);
        m.put("createdAt", createdAtEpochMs);
        m.put("payload", payload);
        return m;
    }
}
