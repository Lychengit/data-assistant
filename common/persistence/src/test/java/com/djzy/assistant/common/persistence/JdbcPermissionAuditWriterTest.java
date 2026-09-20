package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.djzy.assistant.common.permission.PermissionAuditEntry;
import com.djzy.assistant.common.permission.PermissionDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPermissionAuditWriterTest {

    private static final String H2_INSERT = """
            INSERT INTO permission_audit
                (trace_id, user_id, role_ids, tool_name, decision, reason, scope_snapshot, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Test
    void writesEveryDecisionSynchronously() {
        javax.sql.DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        JdbcPermissionAuditWriter writer = new JdbcPermissionAuditWriter(jdbc, H2_INSERT);

        writer.write(new PermissionAuditEntry(
                "req-1",
                "alice",
                List.of("dept_a", "dept_b"),
                "doctor_performance",
                PermissionDecision.DENY,
                "API_NOT_IN_USER_UNION",
                Map.of("depts", List.of("心内科")),
                Instant.parse("2026-09-19T00:00:00Z")));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM permission_audit");
        assertEquals("req-1", row.get("trace_id"));
        assertEquals("alice", row.get("user_id"));
        assertEquals("DENY", row.get("decision"));
        assertEquals("API_NOT_IN_USER_UNION", row.get("reason"));
        assertEquals("[\"dept_a\",\"dept_b\"]", row.get("role_ids"));
        assertEquals("{\"depts\":[\"心内科\"]}", row.get("scope_snapshot"));
    }
}
