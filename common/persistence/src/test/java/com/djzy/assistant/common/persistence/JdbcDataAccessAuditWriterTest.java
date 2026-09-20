package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.djzy.assistant.common.audit.DataAccessAuditEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDataAccessAuditWriterTest {

    private static final String H2_INSERT = """
            INSERT INTO data_access_audit
                (request_id, trace_id, caller_key_id, user_id, service, http_method, http_path, skill_code,
                 scope_snapshot, outcome, reason, row_count, truncated, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Test
    void writesEveryAccessSynchronously() {
        javax.sql.DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        JdbcDataAccessAuditWriter writer = new JdbcDataAccessAuditWriter(jdbc, H2_INSERT);

        writer.write(new DataAccessAuditEntry(
                "req-1",
                "trace-1",
                "gateway",
                "alice",
                "interface-doctor",
                "POST",
                "/doctor/performance",
                "perf_report",
                Map.of("depts", List.of("心内科", "呼吸科")),
                DataAccessAuditEntry.Outcome.ALLOW,
                null,
                42,
                false,
                Instant.parse("2026-09-19T00:00:00Z")));
        writer.write(new DataAccessAuditEntry(
                "req-2",
                "trace-2",
                "gateway",
                "bob",
                "interface-doctor",
                "POST",
                "/doctor/performance",
                null,
                Map.of(),
                DataAccessAuditEntry.Outcome.DENY,
                "API_NOT_IN_USER_UNION",
                0,
                false,
                Instant.parse("2026-09-19T00:00:01Z")));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM data_access_audit ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("alice", rows.get(0).get("user_id"));
        assertEquals("interface-doctor", rows.get(0).get("service"));
        assertEquals("POST", rows.get(0).get("http_method"));
        assertEquals("/doctor/performance", rows.get(0).get("http_path"));
        assertEquals("ALLOW", rows.get(0).get("outcome"));
        assertEquals("{\"depts\":[\"心内科\",\"呼吸科\"]}", rows.get(0).get("scope_snapshot"));
        assertEquals(42, rows.get(0).get("row_count"));
        assertEquals("DENY", rows.get(1).get("outcome"));
        assertEquals("API_NOT_IN_USER_UNION", rows.get(1).get("reason"));
        assertFalse((Boolean) rows.get(1).get("truncated"));
    }
}