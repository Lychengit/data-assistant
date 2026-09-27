package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.confirm.ConfirmRecord;
import com.djzy.assistant.common.confirm.ConfirmStatus;
import com.djzy.assistant.common.confirm.ConfirmStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    private static final String REGISTER = """
            INSERT INTO pending_confirm
                (confirm_id, session_id, turn_id, user_id, action, summary, status, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
            """;

    /** 凭据活多久：与技能执行脚本里那一次确认同一个口径（30 分钟）。 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

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

    /**
     * 登记一条待确认记录（**写侧**）：agent-service 在「用户点了确认」之后调用，网关随后消费掉它。
     *
     * <p>写侧为什么在这里而不是在网关：凭据是**平台产生、网关消费**（§19.9），而「用户点了确认」
     * 这件事只有 agent-service 知道。两边共用同一张表，所以放在同一个 DAO 里，别处不再手写这段 SQL。
     *
     * <p>状态写 {@code pending}：真正的「已批准」由网关消费那一刻写下——谁消费、什么时候消费，
     * 审计里要能对得上（消费是带条件的 UPDATE，天然一次性）。
     *
     * @param action 操作名（审计用，如 {@code tool.write}）
     * @param ttl 有效期；{@code null} = 30 分钟
     * @return 新凭据的 id
     */
    public String register(String userId, String sessionId, String turnId, String action, String summary, Duration ttl) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("确认凭据必须带上用户与会话");
        }
        String confirmId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(ttl == null ? DEFAULT_TTL : ttl);
        jdbc.update(
                REGISTER,
                confirmId,
                sessionId,
                turnId,
                userId,
                action,
                summary,
                Timestamp.from(expiresAt));
        return confirmId;
    }

    @Override
    public boolean consume(String confirmId, String userId) {
        if (confirmId == null || confirmId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        return jdbc.update(CONSUME, confirmId, userId) == 1;
    }
}
