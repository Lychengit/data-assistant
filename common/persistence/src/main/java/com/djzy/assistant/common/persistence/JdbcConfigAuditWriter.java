package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ConfigAuditWriter} 的 PG 实现（§20.7）：与合规审计同样**同步直写、一条不漏**。
 *
 * <p>{@code before} / {@code after} 是 {@code jsonb} 列，PG 需要显式 cast；
 * 异构库（如测试用 H2）可传入等列序的纯文本 INSERT 语句。
 */
public final class JdbcConfigAuditWriter implements ConfigAuditWriter {

    /** PostgreSQL 方言（默认）。 */
    public static final String POSTGRES_INSERT = """
            INSERT INTO config_audit (who, target, field, before, after, request_id, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final String insertSql;

    public JdbcConfigAuditWriter(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), POSTGRES_INSERT);
    }

    public JdbcConfigAuditWriter(JdbcTemplate jdbc) {
        this(jdbc, POSTGRES_INSERT);
    }

    public JdbcConfigAuditWriter(JdbcTemplate jdbc, String insertSql) {
        this.jdbc = jdbc;
        this.insertSql = insertSql;
    }

    @Override
    public void write(ConfigAuditEntry entry) {
        jdbc.update(
                insertSql,
                entry.who(),
                entry.target(),
                entry.field(),
                json(entry.before()),
                json(entry.after()),
                entry.requestId(),
                Timestamp.from(entry.createdAt()));
    }

    private static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("配置审计 JSON 序列化失败", e);
        }
    }
}
