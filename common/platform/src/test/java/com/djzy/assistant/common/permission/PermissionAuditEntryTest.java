package com.djzy.assistant.common.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 权限审计快照的口径（§0.3-3）：**不许因为快照里有 null 就把这条审计带崩**。 */
class PermissionAuditEntryTest {

    @Test
    void nullValuesInSnapshotAreMeaningfulAndMustSurvive() {
        // 验签失败的真实形态：调用方没带签名头 / API Key 没解析出来
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("keyId", null);
        snapshot.put("timestamp", null);
        snapshot.put("reason", "SIGNATURE_MISMATCH");

        PermissionAuditEntry entry = new PermissionAuditEntry(
                "signature-verify",
                null,
                List.of(),
                "signature-verify",
                PermissionDecision.DENY,
                "SIGNATURE_MISMATCH",
                snapshot,
                Instant.now());

        assertNull(entry.scopeSnapshot().get("keyId"));
        assertNull(entry.scopeSnapshot().get("timestamp"));
        assertEquals("SIGNATURE_MISMATCH", entry.scopeSnapshot().get("reason"));
        // 只读：审计是「当时的快照」，落下去之后不该再被人改
        assertThrows(UnsupportedOperationException.class, () -> entry.scopeSnapshot().put("keyId", "别人"));
    }

    @Test
    void snapshotWithoutValueMeansEmpty() {
        PermissionAuditEntry entry = new PermissionAuditEntry(
                "req-1", "alice", List.of("boss"), "doctor_list", PermissionDecision.ALLOW, null, null, null);
        assertEquals(Map.of(), entry.scopeSnapshot());
        assertEquals(List.of("boss"), entry.roleIds());
    }
}