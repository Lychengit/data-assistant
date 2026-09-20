package com.djzy.assistant.common.permission;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 权限审计记录（§4.2 {@code permission_audit}）。
 *
 * <p>硬约束（§0.3-3）：合规审计**直写 PG、一条不漏**，不参与「日志先行 + 异步落库」的取舍。
 */
public record PermissionAuditEntry(
        String traceId,
        String userId,
        List<String> roleIds,
        String toolName,
        PermissionDecision decision,
        String reason,
        Map<String, Object> scopeSnapshot,
        Instant createdAt) {

    public PermissionAuditEntry {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        scopeSnapshot = scopeSnapshot == null ? Map.of() : snapshot(scopeSnapshot);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * 审计快照的不可变副本（与 {@code ConfigAuditEntry#snapshot} 同一条口径）。
     *
     * <p>**不能用 {@code Map.copyOf}**：验签失败这类记录里，字段本来就可能是空的——
     * 调用方没带签名头、API Key 没解析出来，快照里的 {@code null} 是**有意义的取值**（就是「没给」）。
     * 而 {@code Map.copyOf} 遇到 null 值抛 NPE，一个空字段就能让整条审计写不进去：
     * 实测网关收到不带签名的探测请求时审计抛 NPE，只落一条 ERROR 日志，
     * {@code permission_audit} 里一行都没有——最该留痕的安全事件反而最不留痕（§0.3-3 一条不漏）。
     */
    private static Map<String, Object> snapshot(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value));
    }
}
