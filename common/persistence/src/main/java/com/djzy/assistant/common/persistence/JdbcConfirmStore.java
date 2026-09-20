package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.confirm.ConfirmRecord;
import com.djzy.assistant.common.confirm.ConfirmStatus;
import com.djzy.assistant.common.confirm.ConfirmStore;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ConfirmStore} 的 PG 实现（pending_confirm，§19.9 / W1）。
 *
 * <p>消费用一条带条件的 UPDATE 完成，天然并发安全、天然一次性：确认只能被同一个用户消费一次。
 */
public final class JdbcConfirmStore implements ConfirmStore {

    private static final String FIND = """
            SELECT confirm_id, session_id, turn_id, user_id, action, summary, status, expires_at
              FROM pending_confirm
             WHERE confirm_id = ?
            """;

    private static final String CONSUME = """
            UPDATE pending_confirm
               SET status = 'approved', resolved_at = now()
             WHERE confirm_id = ? AND user_id = ? AND status = 'pending'
               AND (expires_at IS NULL OR expires_at > now())
            """;

    private final JdbcTemplate jdbc;

    public JdbcConfirmStore(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcConfirmStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ConfirmRecord> find(String confirmId) {
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        List<ConfirmRecord> rows = jdbc.query(FIND, (rs, rowNum) -> {
            Timestamp expiresAt = rs.getTimestamp("expires_at");
            return new ConfirmRecord(
                    rs.getString("confirm_id"),
                    rs.getString("session_id"),
                    rs.getString("turn_id"),
                    rs.getString("user_id"),
                    rs.getString("action"),
                    rs.getString("summary"),
                    ConfirmStatus.valueOf(rs.getString("status").toUpperCase()),
                    expiresAt == null ? null : expiresAt.toInstant());
        }, confirmId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean consume(String confirmId, String userId) {
        if (confirmId == null || confirmId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        return jdbc.update(CONSUME, confirmId, userId) == 1;
    }
}
