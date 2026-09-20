package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.identity.RefreshTokenStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link RefreshTokenStore} 的 PG 实现（{@code refresh_token}，§19.4）。 */
public final class JdbcRefreshTokenStore implements RefreshTokenStore {

    private static final String INSERT = """
            INSERT INTO refresh_token (token_hash, user_id, expires_at)
            VALUES (?, ?, ?)
            """;

    private static final String FIND_USER = """
            SELECT user_id
              FROM refresh_token
             WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
            """;

    private static final String CONSUME = """
            UPDATE refresh_token
               SET revoked_at = ?
             WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
            """;

    private static final String REVOKE = """
            UPDATE refresh_token
               SET revoked_at = ?
             WHERE token_hash = ? AND revoked_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public JdbcRefreshTokenStore(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcRefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String tokenHash, String userId, Instant expiresAt) {
        jdbc.update(INSERT, tokenHash, userId, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<String> consume(String tokenHash, Instant now) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        Timestamp nowTs = Timestamp.from(now);
        List<String> owners = jdbc.query(FIND_USER, (rs, rowNum) -> rs.getString("user_id"), tokenHash, nowTs);
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        // 条件更新保证「一次性」：并发重放只能有一方把 revoked_at 从 NULL 改掉。
        int updated = jdbc.update(CONSUME, nowTs, tokenHash, nowTs);
        return updated == 1 ? Optional.of(owners.get(0)) : Optional.empty();
    }

    @Override
    public void revoke(String tokenHash, Instant now) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return;
        }
        jdbc.update(REVOKE, Timestamp.from(now), tokenHash);
    }
}
