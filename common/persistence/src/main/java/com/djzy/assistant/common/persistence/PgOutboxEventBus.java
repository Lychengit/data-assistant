package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.bus.AgentEventCodec;
import com.djzy.assistant.common.bus.EventQueue;
import com.djzy.assistant.common.bus.QueueRecord;
import com.djzy.assistant.common.bus.QueueStats;
import com.djzy.assistant.spi.AgentEvent;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PG Outbox 队列（§19.6 / ADR-10）：事件先落 {@code event_outbox} 表，消费者再从表里拉。
 *
 * <p>为什么队列是**表**而不是消息中间件：骨架期要的是「事件不丢 + 可重放 + 零额外中间件」，
 * 表天然满足（事务内写入、重启不丢、按 {@code event_id} 幂等）。Redis Stream 是同一端口下的另一种实现。
 *
 * <p>三个必须知道的口径：
 * <ul>
 *   <li>{@code poll} **不加行锁**：语义是 at-least-once，重复投递由事实表的 {@code event_id} 唯一键吸收
 *       （多消费者只会做重复功，不会写重，§19.6）；
 *   <li>失败走 {@code nack}：{@code attempts + 1} 并退避，到上限由消费者调 {@link #deadLetter}；
 *   <li>{@code deadLetter} 先写死信表再标已投递：中间崩溃只会留下重复死信（可见），不会丢事件。
 * </ul>
 *
 * <p>退避判断用**数据库时钟**（{@code next_attempt_at <= now()}）而不是应用传入的时间戳：
 * 应用与 PG 的时区/时钟只要有一点不一致，退避窗口就会变成 8 小时静默积压。
 *
 * <p>默认 SQL 是 PostgreSQL 方言（{@code ?::jsonb}）；测试用 H2 时传 {@code postgres=false}
 * 走等列序的纯文本变体，与 {@link JdbcConfigAuditWriter} 同一套做法。
 */
public final class PgOutboxEventBus implements EventQueue {

    public static final String TYPE = "pg-outbox";

    /** 退避上限：重试间隔按 2^n 秒增长，但不超过 30s。 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    static final String PG_INSERT = """
            INSERT INTO event_outbox (event_id, session_id, turn_id, payload, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    static final String H2_INSERT = """
            MERGE INTO event_outbox (event_id, session_id, turn_id, payload, created_at)
            KEY (event_id) VALUES (?, ?, ?, ?, ?)
            """;

    static final String PG_DEAD_LETTER = """
            INSERT INTO event_dead_letter (event_id, payload, error, retry_count, created_at)
            VALUES (?, ?::jsonb, ?, ?, ?)
            """;

    static final String H2_DEAD_LETTER = """
            INSERT INTO event_dead_letter (event_id, payload, error, retry_count, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String POLL = """
            SELECT id, payload, attempts
              FROM event_outbox
             WHERE delivered_at IS NULL AND next_attempt_at <= now()
             ORDER BY id
             LIMIT ?
            """;

    private static final String SELECT_PAGE = """
            SELECT id, payload, attempts
              FROM event_outbox
             WHERE delivered_at IS NULL AND event_id = ?
            """;

    private static final String ACK = "UPDATE event_outbox SET delivered_at = ? WHERE id = ?";

    private static final String NACK = """
            UPDATE event_outbox
               SET attempts = attempts + 1, next_attempt_at = ?, last_error = ?
             WHERE id = ?
            """;

    private static final String MARK_DELIVERED = "UPDATE event_outbox SET delivered_at = ? WHERE id = ?";

    private static final String PENDING_STATS =
            "SELECT count(*) AS pending, min(created_at) AS oldest FROM event_outbox WHERE delivered_at IS NULL";

    private static final String DEAD_LETTER_COUNT = "SELECT count(*) FROM event_dead_letter";

    private final JdbcTemplate jdbc;
    private final String insertSql;
    private final String deadLetterSql;

    public PgOutboxEventBus(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), true);
    }

    public PgOutboxEventBus(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    public PgOutboxEventBus(JdbcTemplate jdbc, boolean postgres) {
        this.jdbc = jdbc;
        this.insertSql = postgres ? PG_INSERT : H2_INSERT;
        this.deadLetterSql = postgres ? PG_DEAD_LETTER : H2_DEAD_LETTER;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** 入队。重复入队（重放）不产生第二行：{@code event_id} 唯一。 */
    @Override
    public void publish(AgentEvent event) {
        jdbc.update(
                insertSql,
                event.eventId(),
                event.sessionId(),
                event.turnId(),
                AgentEventCodec.encode(event),
                Timestamp.from(java.time.Instant.ofEpochMilli(event.timestampEpochMs())));
    }

    @Override
    public List<QueueRecord> poll(int max) {
        if (max <= 0) {
            return List.of();
        }
        return jdbc.query(POLL, (rs, rowNum) -> new QueueRecord(
                        rs.getString("id"),
                        AgentEventCodec.decode(rs.getString("payload")),
                        rs.getInt("attempts")),
                max);
    }

    @Override
    public void ack(List<QueueRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(java.time.Instant.now());
        jdbc.batchUpdate(ACK, records, records.size(), (ps, record) -> {
            ps.setTimestamp(1, now);
            // 位点在 PG 侧是 BIGINT：必须按数字绑定，否则 PG 报「bigint = character varying」
            // （H2 会宽容地隐式转换，所以只有真库跑得出来）
            ps.setLong(2, numericId(record));
        });
    }

    @Override
    public void nack(List<QueueRecord> records, String reason) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(records.size());
        for (QueueRecord record : records) {
            Duration backoff = backoffFor(record.attempts() + 1);
            args.add(new Object[] {
                Timestamp.from(java.time.Instant.now().plus(backoff)), truncate(reason), numericId(record)
            });
        }
        jdbc.batchUpdate(NACK, args);
    }

    @Override
    public void deadLetter(QueueRecord record, String reason) {
        jdbc.update(
                deadLetterSql,
                record.eventId(),
                AgentEventCodec.encode(record.event()),
                truncate(reason),
                record.attempts() + 1,
                Timestamp.from(java.time.Instant.now()));
        jdbc.update(MARK_DELIVERED, Timestamp.from(java.time.Instant.now()), numericId(record));
    }

    /** 队列位点在 PG 侧是 {@code BIGSERIAL}；QueueRecord.id 是对外不透明的字符串，这里只做本实现的解析。 */
    private static long numericId(QueueRecord record) {
        try {
            return Long.parseLong(record.id());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PG Outbox 的位点必须是数字 id，实际收到：" + record.id(), e);
        }
    }

    @Override
    public QueueStats stats() {
        Long dead = jdbc.queryForObject(DEAD_LETTER_COUNT, Long.class);
        return jdbc.query(PENDING_STATS, rs -> {
            if (!rs.next()) {
                return new QueueStats(0L, dead == null ? 0L : dead, 0L);
            }
            Timestamp oldest = rs.getTimestamp("oldest");
            return new QueueStats(
                    rs.getLong("pending"),
                    dead == null ? 0L : dead,
                    oldest == null ? 0L : oldest.getTime());
        });
    }

    /** 单条查询（运维排查用）：按 event_id 看它还在不在队列里。 */
    public List<QueueRecord> find(String eventId) {
        return jdbc.query(SELECT_PAGE, (rs, rowNum) -> new QueueRecord(
                        rs.getString("id"),
                        AgentEventCodec.decode(rs.getString("payload")),
                        rs.getInt("attempts")),
                eventId);
    }

    private static Duration backoffFor(int attempt) {
        long seconds = (long) Math.min(MAX_BACKOFF.toSeconds(), Math.pow(2, Math.max(0, attempt - 1)));
        return Duration.ofSeconds(Math.max(1L, seconds));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
