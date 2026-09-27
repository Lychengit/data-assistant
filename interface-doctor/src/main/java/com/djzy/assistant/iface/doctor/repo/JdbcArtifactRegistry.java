package com.djzy.assistant.iface.doctor.repo;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ArtifactRegistry} 的 PG 实现。
 *
 * <p>一列一个占位符，没有任何字符串拼装的机会（§4.3 L2）——这份数据里 {@code name} 是用户可控的。
 */
public final class JdbcArtifactRegistry implements ArtifactRegistry {

    private static final String INSERT = "INSERT INTO artifact"
            + " (artifact_id, session_id, turn_id, name, size_bytes, locator, expires_at, created_at)"
            + " VALUES (?, ?, NULL, ?, ?, ?, ?, now())";

    private final JdbcTemplate jdbc;

    public JdbcArtifactRegistry(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void register(
            String artifactId,
            String sessionId,
            String name,
            long sizeBytes,
            String locator,
            Instant expiresAt) {
        jdbc.update(
                INSERT,
                artifactId,
                sessionId,
                name,
                sizeBytes,
                locator,
                expiresAt == null ? null : Timestamp.from(expiresAt));
    }
}