package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.permission.PermissionAuditEntry;
import com.djzy.assistant.common.permission.PermissionAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link PermissionAuditWriter} 的 PG 实现：**同步直写、一条不漏**（§0.3-3 / §20.4），
 * 失败即抛错告警——不参与「日志先行 + 异步落库」的取舍。
 *
 * <p>{@code role_ids} / {@code scope_snapshot} 是 {@code jsonb} 列，PG 需要显式 cast；
 * 异构库（如测试用 H2）可传入等列序的纯文本 INSERT 语句。
 */
public final class JdbcPermissionAuditWriter implements PermissionAuditWriter {

    /** PostgreSQL 方言（默认）。 */
    public static final String POSTGRES_INSERT = """
            INSERT INTO permission_audit
                (trace_id, user_id, role_ids, tool_name, decision, reason, scope_snapshot, created_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final String insertSql;

    public JdbcPermissionAuditWriter(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), POSTGRES_INSERT);
    }

    public JdbcPermissionAuditWriter(JdbcTemplate jdbc) {
        this(jdbc, POSTGRES_INSERT);
    }

    public JdbcPermissionAuditWriter(JdbcTemplate jdbc, String insertSql) {
        this.jdbc = jdbc;
        this.insertSql = insertSql;
    }

    @Override
    public void write(PermissionAuditEntry entry) {
        jdbc.update(
                insertSql,
                entry.traceId(),
                entry.userId(),
                json(entry.roleIds()),
                entry.toolName(),
                entry.decision() == null ? null : entry.decision().name(),
                entry.reason(),
                json(entry.scopeSnapshot()),
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
