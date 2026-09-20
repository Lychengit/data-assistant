package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.audit.AuditReadEntry;
import com.djzy.assistant.common.audit.AuditReadWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AuditReadWriter} 的 PG 实现（{@code audit_read_audit}，§20.4）。
 *
 * <p>写在**读之前**：先落「谁要查审计」，再查审计内容。这样即便查询超时/崩溃，
 * 也能留下「有人尝试翻看审计」的痕迹。
 */
public final class JdbcAuditReadWriter implements AuditReadWriter {

    /** PostgreSQL 方言（默认）：{@code params} 是 {@code jsonb}，需要显式 cast。 */
    public static final String POSTGRES_INSERT = """
            INSERT INTO audit_read_audit (who, action, params, outcome, row_count, reason, created_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final String insertSql;

    public JdbcAuditReadWriter(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), POSTGRES_INSERT);
    }

    public JdbcAuditReadWriter(JdbcTemplate jdbc, String insertSql) {
        this.jdbc = jdbc;
        this.insertSql = insertSql;
    }

    @Override
    public void write(AuditReadEntry entry) {
        jdbc.update(
                insertSql,
                entry.who(),
                entry.action(),
                json(entry.params()),
                entry.outcome().name(),
                entry.rowCount(),
                entry.reason(),
                Timestamp.from(entry.createdAt()));
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? null : value);
        } catch (Exception e) {
            return null;
        }
    }
}
