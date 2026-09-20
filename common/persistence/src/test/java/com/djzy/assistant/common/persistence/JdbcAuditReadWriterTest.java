package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.audit.AuditReadEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 审计读留痕（§20.4）：谁在何时、用什么条件查了审计，必须写得进去、也读得出来。 */
class JdbcAuditReadWriterTest {

    private static final String H2_INSERT = """
            INSERT INTO audit_read_audit (who, action, params, outcome, row_count, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    @Test
    void writesWhoReadWhatAndHowManyRows() {
        javax.sql.DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        JdbcAuditReadWriter writer = new JdbcAuditReadWriter(jdbc, H2_INSERT);

        writer.write(new AuditReadEntry(
                "admin",
                "data-access",
                Map.of("from", "2026-09-01T00:00:00Z", "to", "2026-09-02T00:00:00Z", "apiCode", "doctor_performance"),
                AuditReadEntry.Outcome.ALLOW,
                7,
                null,
                Instant.parse("2026-09-19T00:00:00Z")));
        // 被拒的尝试也要留痕，但 reason 必须写清（否则事后无法解释为什么拒绝）
        writer.write(new AuditReadEntry(
                "alice",
                "overview",
                Map.of("from", "2026-09-01T00:00:00Z", "to", "2026-09-02T00:00:00Z"),
                AuditReadEntry.Outcome.DENY,
                0,
                "非 admin 访问审计入口",
                Instant.parse("2026-09-19T00:00:01Z")));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM audit_read_audit ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("admin", rows.get(0).get("who"));
        assertEquals("ALLOW", rows.get(0).get("outcome"));
        assertEquals(7, rows.get(0).get("row_count"));
        // 查询条件快照必须落库（事后能回答「当时是按什么条件查的」）
        assertTrue(((String) rows.get(0).get("params")).contains("doctor_performance"));
        assertEquals("alice", rows.get(1).get("who"));
        assertEquals("DENY", rows.get(1).get("outcome"));
        assertEquals("非 admin 访问审计入口", rows.get(1).get("reason"));
    }
}
