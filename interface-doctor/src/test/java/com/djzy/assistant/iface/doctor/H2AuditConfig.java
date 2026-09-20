package com.djzy.assistant.iface.doctor;

import com.djzy.assistant.common.audit.DataAccessAuditWriter;
import com.djzy.assistant.common.persistence.JdbcDataAccessAuditWriter;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试库适配：PG 的 {@code ?::jsonb} 在 H2 里会被当成「转成 JSON 标量」，
 * 存进 VARCHAR 后读出的是**再包一层引号**的字符串（不是 PG 的行为）。
 *
 * <p>因此测试用等列序的纯文本 INSERT；生产仍走 {@link JdbcDataAccessAuditWriter#POSTGRES_INSERT}。
 */
@TestConfiguration
public class H2AuditConfig {

    private static final String H2_INSERT = """
            INSERT INTO data_access_audit
                (request_id, trace_id, caller_key_id, user_id, service, http_method, http_path, skill_code,
                 scope_snapshot, outcome, reason, row_count, truncated, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Bean
    @Primary
    public DataAccessAuditWriter h2DataAccessAuditWriter(DataSource dataSource) {
        return new JdbcDataAccessAuditWriter(new JdbcTemplate(dataSource), H2_INSERT);
    }
}