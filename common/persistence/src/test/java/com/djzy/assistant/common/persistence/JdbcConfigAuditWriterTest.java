package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.djzy.assistant.common.config.ConfigAuditEntry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 配置变更审计（§20.7）：记下「谁把哪个配置从什么改成了什么」。 */
class JdbcConfigAuditWriterTest {

    private static final String H2_INSERT = """
            INSERT INTO config_audit (who, target, field, before, after, request_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    @Test
    void recordsBeforeAndAfter() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcConfigAuditWriter writer = new JdbcConfigAuditWriter(jdbc, H2_INSERT);

        writer.write(new ConfigAuditEntry(
                "admin",
                "role",
                "role_api:doctor_performance",
                Map.of("scope", Map.of("depts", java.util.List.of("心内科"))),
                Map.of("scope", Map.of("depts", java.util.List.of("心内科", "呼吸科"))),
                "req-1",
                Instant.parse("2026-09-19T00:00:00Z")));
        writer.write(new ConfigAuditEntry("admin", "role", "role_api:doctor_list", null, null, "req-2", Instant.now()));

        var rows = jdbc.queryForList("SELECT * FROM config_audit ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("admin", rows.get(0).get("who"));
        assertEquals("role", rows.get(0).get("target"));
        assertEquals("role_api:doctor_performance", rows.get(0).get("field"));
        assertEquals("{\"scope\":{\"depts\":[\"心内科\"]}}", rows.get(0).get("before"));
        assertEquals("{\"scope\":{\"depts\":[\"心内科\",\"呼吸科\"]}}", rows.get(0).get("after"));
        assertEquals("req-1", rows.get(0).get("request_id"));
        assertNull(rows.get(1).get("before"));
    }
}
