package com.djzy.assistant.management.audit;

import com.djzy.assistant.common.api.ApiRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审计查询的 JDBC 实现（**只读连接**，§18.4.6 / §20.4）。
 *
 * <p>三条口径写在 SQL 里而不是文档里：
 * <ul>
 *   <li>每个查询都带 {@code created_at >= ? AND created_at < ?}（左闭右开，§6.6；也为将来月分区裁剪留口子，§20.5）；
 *   <li>**不 JOIN 业务表、不套范围过滤**：审计记录不挂在业务对象树上；
 *   <li>全部 {@code ORDER BY created_at DESC, id DESC} + {@code LIMIT}，避免管理端一次拉爆内存。
 * </ul>
 */
public final class JdbcAuditQueryRepository implements AuditQueryRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DATA_ACCESS_COLUMNS = """
            id, request_id, trace_id, caller_key_id, user_id, service, http_method, http_path, skill_code,
            scope_snapshot, outcome, reason, row_count, truncated, created_at
            """;

    private static final String DATA_ACCESS = "SELECT " + DATA_ACCESS_COLUMNS + " FROM data_access_audit WHERE created_at >= ? AND created_at < ?";

    private static final String PERMISSION_COLUMNS = """
            id, trace_id, user_id, role_ids, tool_name, decision, reason, scope_snapshot, created_at
            """;

    private static final String PERMISSION = "SELECT " + PERMISSION_COLUMNS + " FROM permission_audit WHERE created_at >= ? AND created_at < ?";

    private static final String CONFIG_COLUMNS = "id, who, target, field, before, after, request_id, created_at";

    private static final String CONFIG = "SELECT " + CONFIG_COLUMNS + " FROM config_audit WHERE created_at >= ? AND created_at < ?";

    private static final String AUDIT_READ = """
            SELECT id, who, action, params, outcome, row_count, reason, created_at
              FROM audit_read_audit
             WHERE created_at >= ? AND created_at < ?
            """;

    private static final String TAIL = " ORDER BY created_at DESC, id DESC LIMIT ?";

    private final JdbcTemplate jdbc;

    public JdbcAuditQueryRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcAuditQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<Map<String, Object>> dataAccess(
            AuditWindow window, String requestId, String userId, String httpPath, String outcome) {
        StringBuilder sql = new StringBuilder(DATA_ACCESS);
        List<Object> args = new ArrayList<>(List.of(start(window), end(window)));
        addEquals(sql, args, "request_id", requestId);
        addEquals(sql, args, "user_id", userId);
        addEquals(sql, args, "http_path", httpPath);
        addEquals(sql, args, "outcome", outcome == null ? null : outcome.trim().toUpperCase(java.util.Locale.ROOT));
        sql.append(TAIL);
        args.add(window.limit());
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("requestId", rs.getString("request_id"));
            row.put("traceId", rs.getString("trace_id"));
            row.put("callerKeyId", rs.getString("caller_key_id"));
            row.put("userId", rs.getString("user_id"));
            row.put("service", rs.getString("service"));
            row.put("httpMethod", rs.getString("http_method"));
            row.put("httpPath", rs.getString("http_path"));
            row.put("skillCode", rs.getString("skill_code"));
            row.put("scope", json(rs.getString("scope_snapshot")));
            row.put("outcome", rs.getString("outcome"));
            row.put("reason", rs.getString("reason"));
            row.put("rowCount", rs.getInt("row_count"));
            row.put("truncated", rs.getBoolean("truncated"));
            row.put("createdAt", instant(rs.getTimestamp("created_at")));
            return row;
        }, args.toArray());
    }

    @Override
    public List<Map<String, Object>> permissionAudits(AuditWindow window, String traceId, String userId, String decision) {
        StringBuilder sql = new StringBuilder(PERMISSION);
        List<Object> args = new ArrayList<>(List.of(start(window), end(window)));
        addEquals(sql, args, "trace_id", traceId);
        addEquals(sql, args, "user_id", userId);
        addEquals(sql, args, "decision", decision == null ? null : decision.trim().toUpperCase(java.util.Locale.ROOT));
        sql.append(TAIL);
        args.add(window.limit());
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("traceId", rs.getString("trace_id"));
            row.put("userId", rs.getString("user_id"));
            row.put("roleIds", json(rs.getString("role_ids")));
            row.put("toolName", rs.getString("tool_name"));
            row.put("decision", rs.getString("decision"));
            row.put("reason", rs.getString("reason"));
            row.put("scope", json(rs.getString("scope_snapshot")));
            row.put("createdAt", instant(rs.getTimestamp("created_at")));
            return row;
        }, args.toArray());
    }

    @Override
    public List<Map<String, Object>> configAudits(AuditWindow window, String target, String who) {
        StringBuilder sql = new StringBuilder(CONFIG);
        List<Object> args = new ArrayList<>(List.of(start(window), end(window)));
        addEquals(sql, args, "target", target);
        addEquals(sql, args, "who", who);
        sql.append(TAIL);
        args.add(window.limit());
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("who", rs.getString("who"));
            row.put("target", rs.getString("target"));
            row.put("field", rs.getString("field"));
            row.put("before", json(rs.getString("before")));
            row.put("after", json(rs.getString("after")));
            row.put("requestId", rs.getString("request_id"));
            row.put("createdAt", instant(rs.getTimestamp("created_at")));
            return row;
        }, args.toArray());
    }

    @Override
    public List<Map<String, Object>> auditReads(AuditWindow window, String who) {
        StringBuilder sql = new StringBuilder(AUDIT_READ);
        List<Object> args = new ArrayList<>(List.of(start(window), end(window)));
        addEquals(sql, args, "who", who);
        sql.append(TAIL);
        args.add(window.limit());
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("who", rs.getString("who"));
            row.put("action", rs.getString("action"));
            row.put("params", json(rs.getString("params")));
            row.put("outcome", rs.getString("outcome"));
            row.put("rowCount", rs.getInt("row_count"));
            row.put("reason", rs.getString("reason"));
            row.put("createdAt", instant(rs.getTimestamp("created_at")));
            return row;
        }, args.toArray());
    }

    @Override
    public Map<String, Object> trace(String requestId, String traceId, AuditWindow window) {
        Map<String, Object> chain = new LinkedHashMap<>();
        // 数据访问以 requestId 串联（§20.4）；编排侧其余事实表以 traceId 串联（§8.3）。
        chain.put("dataAccess", dataAccess(window, requestId, null, null, null));
        chain.put("permissionAudits", permissionAudits(window, traceId, null, null));
        chain.put("toolCalls", traceId == null || traceId.isBlank() ? List.of() : toolCalls(traceId, window));
        chain.put("llmCalls", traceId == null || traceId.isBlank() ? List.of() : llmCalls(traceId, window));
        chain.put("steps", traceId == null || traceId.isBlank() ? List.of() : steps(traceId, window));
        chain.put("turns", traceId == null || traceId.isBlank() ? List.of() : turns(traceId, window));
        chain.put("mismatches", mismatches(chain));
        return chain;
    }

    /**
     * 链路一致性告警（§20.4：两者以 requestId 关联，**不一致即告警**）。
     *
     * <p>骨架期判据：agent 侧记了接口类工具调用（{@code iface_*}），接口服务却没有对应的
     * {@code data_access_audit} 记录 → 告警。反向（接口服务有记录、编排侧没有）多为技能/直连调用，不算异常。
     */
    private static List<Map<String, Object>> mismatches(Map<String, Object> chain) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) chain.get("toolCalls");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataAccess = (List<Map<String, Object>>) chain.get("dataAccess");
        long ifaceCalls = toolCalls.stream()
                .map(row -> String.valueOf(row.get("toolName")))
                .filter(name -> name.startsWith(ApiRoute.TOOL_PREFIX))
                .count();
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (ifaceCalls > dataAccess.size()) {
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("code", "AGENT_IFACE_CALL_WITHOUT_DATA_ACCESS_AUDIT");
            warning.put("message", "agent 侧记了接口调用，接口服务没有对应的数据访问审计（§20.4 不一致告警）");
            warning.put("agentIfaceCalls", ifaceCalls);
            warning.put("dataAccessRecords", dataAccess.size());
            warnings.add(warning);
        }
        long denied = dataAccess.stream().filter(row -> !"ALLOW".equals(row.get("outcome"))).count();
        if (denied > 0) {
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("code", "DATA_ACCESS_NOT_ALLOWED");
            warning.put("message", "本链路存在被拒或失败的数据访问");
            warning.put("count", denied);
            warnings.add(warning);
        }
        return warnings;
    }

    private List<Map<String, Object>> toolCalls(String traceId, AuditWindow window) {
        return jdbc.query(
                """
                SELECT id, trace_id, turn_id, tool_name, args, result_size, status, error, latency_ms, created_at
                  FROM tool_call
                 WHERE trace_id = ? AND created_at >= ? AND created_at < ?
                 ORDER BY created_at, id
                 LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("turnId", rs.getString("turn_id"));
                    row.put("toolName", rs.getString("tool_name"));
                    row.put("args", json(rs.getString("args")));
                    row.put("resultSize", rs.getObject("result_size"));
                    row.put("status", rs.getString("status"));
                    row.put("error", rs.getString("error"));
                    row.put("latencyMs", rs.getObject("latency_ms"));
                    row.put("createdAt", instant(rs.getTimestamp("created_at")));
                    return row;
                },
                traceId,
                start(window),
                end(window),
                window.limit());
    }

    private List<Map<String, Object>> llmCalls(String traceId, AuditWindow window) {
        return jdbc.query(
                """
                SELECT id, turn_id, model, prompt_hash, prompt_preview, response_preview,
                       tokens_in, tokens_out, cost, latency_ms, status, created_at
                  FROM llm_call
                 WHERE trace_id = ? AND created_at >= ? AND created_at < ?
                 ORDER BY created_at, id
                 LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("turnId", rs.getString("turn_id"));
                    row.put("model", rs.getString("model"));
                    row.put("promptHash", rs.getString("prompt_hash"));
                    row.put("promptPreview", rs.getString("prompt_preview"));
                    row.put("responsePreview", rs.getString("response_preview"));
                    row.put("tokensIn", rs.getObject("tokens_in"));
                    row.put("tokensOut", rs.getObject("tokens_out"));
                    row.put("cost", rs.getBigDecimal("cost"));
                    row.put("latencyMs", rs.getObject("latency_ms"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", instant(rs.getTimestamp("created_at")));
                    return row;
                },
                traceId,
                start(window),
                end(window),
                window.limit());
    }

    private List<Map<String, Object>> steps(String traceId, AuditWindow window) {
        return jdbc.query(
                """
                SELECT id, turn_id, seq, type, content, tool_name, tool_args, tool_result, created_at
                  FROM agent_step
                 WHERE trace_id = ? AND created_at >= ? AND created_at < ?
                 ORDER BY seq, id
                 LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("turnId", rs.getString("turn_id"));
                    row.put("seq", rs.getLong("seq"));
                    row.put("type", rs.getString("type"));
                    row.put("content", rs.getString("content"));
                    row.put("toolName", rs.getString("tool_name"));
                    row.put("toolArgs", json(rs.getString("tool_args")));
                    row.put("toolResult", json(rs.getString("tool_result")));
                    row.put("createdAt", instant(rs.getTimestamp("created_at")));
                    return row;
                },
                traceId,
                start(window),
                end(window),
                window.limit());
    }

    private List<Map<String, Object>> turns(String traceId, AuditWindow window) {
        return jdbc.query(
                """
                SELECT id, session_id, turn_id, user_input, final_answer, figures_json, expressions_json,
                       ledger_json, verify_result, latency_ms, created_at
                  FROM conversation_turn
                 WHERE trace_id = ? AND created_at >= ? AND created_at < ?
                 ORDER BY created_at, id
                 LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("turnId", rs.getString("turn_id"));
                    row.put("userInput", rs.getString("user_input"));
                    row.put("finalAnswer", rs.getString("final_answer"));
                    row.put("figures", json(rs.getString("figures_json")));
                    row.put("expressions", json(rs.getString("expressions_json")));
                    row.put("ledger", json(rs.getString("ledger_json")));
                    row.put("verifyResult", json(rs.getString("verify_result")));
                    row.put("latencyMs", rs.getObject("latency_ms"));
                    row.put("createdAt", instant(rs.getTimestamp("created_at")));
                    return row;
                },
                traceId,
                start(window),
                end(window),
                window.limit());
    }

    @Override
    public Map<String, Object> overview(AuditWindow window) {
        Map<String, Object> overview = new LinkedHashMap<>();
        Map<String, Object> dataAccess = new LinkedHashMap<>();
        dataAccess.put("total", count("SELECT count(*) FROM data_access_audit WHERE created_at >= ? AND created_at < ?", window));
        dataAccess.put("allowed", count("SELECT count(*) FROM data_access_audit WHERE created_at >= ? AND created_at < ? AND outcome = 'ALLOW'", window));
        dataAccess.put("denied", count("SELECT count(*) FROM data_access_audit WHERE created_at >= ? AND created_at < ? AND outcome = 'DENY'", window));
        dataAccess.put("errors", count("SELECT count(*) FROM data_access_audit WHERE created_at >= ? AND created_at < ? AND outcome = 'ERROR'", window));
        dataAccess.put("truncated", count("SELECT count(*) FROM data_access_audit WHERE created_at >= ? AND created_at < ? AND truncated = TRUE", window));
        overview.put("dataAccess", dataAccess);

        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("total", count("SELECT count(*) FROM permission_audit WHERE created_at >= ? AND created_at < ?", window));
        permission.put("denied", count("SELECT count(*) FROM permission_audit WHERE created_at >= ? AND created_at < ? AND decision = 'DENY'", window));
        // 拦截数 = 判定拒绝 + 数据访问被拒（§18.4.6 M6）
        overview.put("permission", permission);
        overview.put("blocked", (Long) permission.get("denied") + (Long) dataAccess.get("denied"));

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("calls", count("SELECT count(*) FROM llm_call WHERE created_at >= ? AND created_at < ?", window));
        llm.put("failed", count("SELECT count(*) FROM llm_call WHERE created_at >= ? AND created_at < ? AND status IS NOT NULL AND status <> 'ok'", window));
        llm.put("tokensIn", decimal("SELECT coalesce(sum(tokens_in), 0) FROM llm_call WHERE created_at >= ? AND created_at < ?", window));
        llm.put("tokensOut", decimal("SELECT coalesce(sum(tokens_out), 0) FROM llm_call WHERE created_at >= ? AND created_at < ?", window));
        llm.put("cost", decimal("SELECT coalesce(sum(cost), 0) FROM llm_call WHERE created_at >= ? AND created_at < ?", window));
        List<Long> latencies = latencies("SELECT latency_ms FROM llm_call WHERE created_at >= ? AND created_at < ? AND latency_ms IS NOT NULL", window);
        llm.put("latencyP50Ms", percentile(latencies, 50));
        llm.put("latencyP95Ms", percentile(latencies, 95));
        overview.put("llm", llm);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("calls", count("SELECT count(*) FROM tool_call WHERE created_at >= ? AND created_at < ?", window));
        tools.put("failed", count("SELECT count(*) FROM tool_call WHERE created_at >= ? AND created_at < ? AND status IS NOT NULL AND status <> 'ok'", window));
        tools.put("ifaceCalls", count("SELECT count(*) FROM tool_call WHERE created_at >= ? AND created_at < ? AND tool_name LIKE 'iface\\_%'", window));
        overview.put("toolCalls", tools);

        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("changes", count("SELECT count(*) FROM config_audit WHERE created_at >= ? AND created_at < ?", window));
        configs.put("auditReads", count("SELECT count(*) FROM audit_read_audit WHERE created_at >= ? AND created_at < ?", window));
        overview.put("config", configs);

        // 跑偏信号（§10.4）：被拒访问、被截断、接口侧重试/失败的调用
        Map<String, Object> drift = new LinkedHashMap<>();
        drift.put("truncatedResults", dataAccess.get("truncated"));
        drift.put("deniedAccess", dataAccess.get("denied"));
        drift.put("failedToolCalls", tools.get("failed"));
        overview.put("driftSignals", drift);

        Map<String, Object> windowBody = new LinkedHashMap<>();
        windowBody.put("from", window.from().toString());
        windowBody.put("to", window.to().toString());
        overview.put("window", windowBody);
        return overview;
    }

    private long count(String sql, AuditWindow window) {
        Long value = jdbc.queryForObject(sql, Long.class, start(window), end(window));
        return value == null ? 0L : value;
    }

    private java.math.BigDecimal decimal(String sql, AuditWindow window) {
        java.math.BigDecimal value = jdbc.queryForObject(sql, java.math.BigDecimal.class, start(window), end(window));
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private List<Long> latencies(String sql, AuditWindow window) {
        // SQL 已过滤 NULL；这里只排序，分位用最近秩算（不依赖各库的百分位函数）
        List<Long> values = new ArrayList<>(
                jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), start(window), end(window)));
        values.sort(Comparator.naturalOrder());
        return values;
    }

    /** 分位用最近秩（nearest-rank）算，避免依赖各库不同的百分位函数。 */
    static long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void addEquals(StringBuilder sql, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        args.add(value.trim());
    }

    private static Timestamp start(AuditWindow window) {
        return Timestamp.from(window.from());
    }

    private static Timestamp end(AuditWindow window) {
        return Timestamp.from(window.to());
    }

    private static String instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    /** JSON 列在 PG 里是 jsonb、在测试库是文本：能解析就返回结构，不能解析就原样返回，绝不失真。 */
    private static Object json(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
