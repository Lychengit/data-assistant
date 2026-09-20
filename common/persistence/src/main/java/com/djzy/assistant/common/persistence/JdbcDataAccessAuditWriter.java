package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.audit.DataAccessAuditEntry;
import com.djzy.assistant.common.audit.DataAccessAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link DataAccessAuditWriter} 的 PG 实现：**同步直写、一条不漏**（§0.3-3 / §20.4），
 * 失败即抛错由调用方告警——不参与「日志先行 + 异步落库」的取舍。
 *
 * <p>{@code scope_snapshot} 是 {@code jsonb} 列，PG 需要显式 cast；
 * 异构库（如测试用 H2）可传入等列序的纯文本 INSERT 语句。
 */
public final class JdbcDataAccessAuditWriter implements DataAccessAuditWriter {

    /** PostgreSQL 方言（默认）。 */
    public static final String POSTGRES_INSERT = """
            INSERT INTO data_access_audit
                (request_id, trace_id, caller_key_id, user_id, service, http_method, http_path,
                 skill_code, scope_snapshot, outcome, reason, row_count, truncated, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final String insertSql;

    public JdbcDataAccessAuditWriter(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), POSTGRES_INSERT);
    }

    public JdbcDataAccessAuditWriter(JdbcTemplate jdbc) {
        this(jdbc, POSTGRES_INSERT);
    }

    public JdbcDataAccessAuditWriter(JdbcTemplate jdbc, String insertSql) {
        this.jdbc = jdbc;
        this.insertSql = insertSql;
    }

    @Override
    public void write(DataAccessAuditEntry entry) {
        jdbc.update(
                insertSql,
                entry.requestId(),
                entry.traceId(),
                entry.callerKeyId(),
                entry.userId(),
                entry.service(),
                entry.httpMethod(),
                entry.httpPath(),
                entry.skillCode(),
                json(entry.scopeSnapshot()),
                entry.outcome().name(),
                entry.reason(),
                entry.rowCount(),
                entry.truncated(),
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