package com.djzy.assistant.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 配置审计快照的口径（§20.7）：**不许因为快照里有 null 就把这次变更带崩**。 */
class ConfigAuditEntryTest {

    @Test
    void nullValuesInSnapshotsAreMeaningfulAndMustSurvive() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("domain", null);
        before.put("name", "利润");
        before.put("aliases", List.of("净利润"));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("domain", "doctor_performance");
        after.put("name", "利润");
        after.put("aliases", null);

        ConfigAuditEntry entry =
                new ConfigAuditEntry("admin", "dictionary", "metric_dictionary:profit", before, after, "req-1", Instant.now());

        assertNull(entry.before().get("domain"));
        assertNull(entry.after().get("aliases"));
        assertEquals("利润", entry.after().get("name"));
        // 只读：调用方不能事后改快照（否则审计就不是当时的快照了）
        assertThrows(UnsupportedOperationException.class, () -> entry.after().put("name", "别的"));
    }

    @Test
    void entryWithoutBeforeOrAfterMeansCreateOrRevoke() {
        ConfigAuditEntry created =
                new ConfigAuditEntry("admin", "api", "sys_api:lab_report", null, Map.of("enabled", true), "req-2", Instant.now());
        assertNull(created.before());
        assertTrue(created.after().containsKey("enabled"));

        ConfigAuditEntry revoked =
                new ConfigAuditEntry("admin", "api", "sys_api:lab_report", Map.of("enabled", true), null, "req-3", Instant.now());
        assertNull(revoked.after());
    }
}
