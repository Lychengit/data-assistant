package com.djzy.assistant.common.persistence;

import com.djzy.assistant.spi.RuntimeStatePort;
import com.djzy.assistant.spi.Snapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 运行时状态的 PG 落点（§19.5 / ADR-32）：{@code (userId, sessionId, key)} → 中立快照。
 *
 * <p>为什么必须落 PG：挂起 / 恢复、服务重启、换 pod、断线续传是同一件事的四种表现（§19.5）。
 * 状态留在进程里，SSE 连接就被钉在某一台副本上；换 pod 等于丢会话（§2.3 隐性单点）。
 *
 * <p>两条口径：
 * <ul>
 *   <li>{@code save} 是**覆盖写**（挂起快照会随轮次推进被替换），所以用 upsert 而不是追加；
 *       主键是寻址三元组——§20.5 那条「事实表主键带 {@code created_at}」针对的是审计类事实表；
 *   <li>{@code runtime_id} / {@code runtime_version} 原样存也原样取回：§19.14 不允许跨运行时恢复，
 *       判定在运行时侧（比对不上就抛 {@code RuntimeMismatchException}），这里不做「尽力而为」的兼容。
 * </ul>
 *
 * <p>默认 SQL 是 PostgreSQL 方言（{@code ?::jsonb} + {@code ON CONFLICT ... DO UPDATE}）；
 * 测试用 H2 时传 {@code postgres=false} 走等列序的 {@code MERGE} 变体，与 {@link PgOutboxEventBus} 同一套做法。
 */
public final class PgRuntimeStatePort implements RuntimeStatePort {

    public static final String TYPE = "pg";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    static final String PG_SAVE = """
            INSERT INTO agent_state (user_id, session_id, state_key, runtime_id, runtime_version,
                                     snapshot_created_at, payload, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (user_id, session_id, state_key) DO UPDATE
               SET runtime_id = EXCLUDED.runtime_id,
                   runtime_version = EXCLUDED.runtime_version,
                   snapshot_created_at = EXCLUDED.snapshot_created_at,
                   payload = EXCLUDED.payload,
                   updated_at = EXCLUDED.updated_at
            """;

    static final String H2_SAVE = """
            MERGE INTO agent_state (user_id, session_id, state_key, runtime_id, runtime_version,
                                    snapshot_created_at, payload, updated_at)
            KEY (user_id, session_id, state_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String LOAD = """
            SELECT runtime_id, runtime_version, session_id, snapshot_created_at, payload
              FROM agent_state
             WHERE user_id = ? AND session_id = ? AND state_key = ?
            """;

    private static final String DELETE = "DELETE FROM agent_state WHERE user_id = ? AND session_id = ? AND state_key = ?";

    /** 会话级清理（运维 / 测试用）：换 pod 排查时先看这个会话到底存了几段状态。 */
    private static final String LIST_KEYS =
            "SELECT state_key FROM agent_state WHERE user_id = ? AND session_id = ? ORDER BY state_key";

    private final JdbcTemplate jdbc;
    private final String saveSql;
    private final ObjectMapper mapper = new ObjectMapper();

    public PgRuntimeStatePort(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), true);
    }

    public PgRuntimeStatePort(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    public PgRuntimeStatePort(JdbcTemplate jdbc, boolean postgres) {
        this.jdbc = jdbc;
        this.saveSql = postgres ? PG_SAVE : H2_SAVE;
    }

    @Override
    public Optional<Snapshot> load(String userId, String sessionId, String key) {
        List<Snapshot> found = jdbc.query(
                LOAD,
                (rs, rowNum) -> new Snapshot(
                        rs.getString("runtime_id"),
                        rs.getString("runtime_version"),
                        rs.getString("session_id"),
                        rs.getLong("snapshot_created_at"),
                        payloadOf(rs.getString("payload"))),
                userId,
                sessionId,
                key);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void save(String userId, String sessionId, String key, Snapshot snapshot) {
        jdbc.update(
                saveSql,
                userId,
                sessionId,
                key,
                snapshot.runtimeId(),
                snapshot.runtimeVersion(),
                snapshot.createdAtEpochMs(),
                write(snapshot.payload()),
                Timestamp.from(Instant.now()));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        jdbc.update(DELETE, userId, sessionId, key);
    }

    /** 会话下已有的状态段（运维 / 会话清理用；端口本身不需要，所以只在本实现上开放）。 */
    public List<String> keysOf(String userId, String sessionId) {
        return jdbc.queryForList(LIST_KEYS, String.class, userId, sessionId);
    }

    private String write(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("运行时快照无法序列化", e);
        }
    }

    /** PG 返回 jsonb 的文本形态，H2 返回纯文本：两边都按 JSON 解，解不开当空载荷（不抛，避免脏数据卡死恢复）。 */
    private Map<String, Object> payloadOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return Map.of();
        }
    }
}