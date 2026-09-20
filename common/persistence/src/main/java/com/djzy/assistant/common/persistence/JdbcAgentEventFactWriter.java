package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.bus.AgentEventFactWriter;
import com.djzy.assistant.spi.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 事件 → PG 事实表（§19.6 / §8.3）：{@code conversation_turn} / {@code agent_step} / {@code tool_call}。
 *
 * <p>**一批一个事务**（调用方保证事务边界），每行按 {@code event_id} 幂等，重复投递不写重。
 *
 * <p>落点只有这几处，其余事件类型只活在本地 append-only 日志里（日志是唯一事实源，事实表是查询视图）：
 * <ul>
 *   <li>{@code TURN_END} → {@code conversation_turn}（一轮的 user_input / final_answer / 台账 / 校验结论）
 *   <li>{@code THOUGHT} → {@code agent_step.type=thought}
 *   <li>{@code TOOL_CALL_END} → {@code agent_step.type=action}（tool_name / tool_args）
 *   <li>{@code TOOL_RESULT} → {@code agent_step.type=observation} + {@code tool_call} 台账行
 *   <li>{@code ERROR} → {@code agent_step.type=observation}
 * </ul>
 *
 * <p>**同一批内做一次轻量补齐**：{@code TURN_START.userText} 补 {@code user_input}，
 * 该轮最后一条 {@code TEXT} 补 {@code final_answer}（运行时没在 TURN_END 里带答案时兜底）。
 * 攒批会把同一轮的这些事件放在一起，配不上就留空——事实表是派生视图，不为了补字段去回查日志。
 *
 * <p>必填列（session_id / turn_id / trace_id）缺失的事件不写事实表，直接跳过：不是错误，
 * 而是这类事件本来就只属于日志。
 */
public final class JdbcAgentEventFactWriter implements AgentEventFactWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TURN_COLUMNS = """
            event_id, session_id, turn_id, trace_id, user_input, final_answer,
            figures_json, expressions_json, ledger_json, verify_result, latency_ms, created_at
            """.trim().replaceAll("\\s+", " ");

    private static final String STEP_COLUMNS = """
            event_id, session_id, turn_id, trace_id, seq, type, content,
            tool_name, tool_args, tool_result, created_at
            """.trim().replaceAll("\\s+", " ");

    private static final String TOOL_COLUMNS = """
            event_id, trace_id, turn_id, tool_name, args, result_size, status, error, latency_ms, created_at
            """.trim().replaceAll("\\s+", " ");

    private static final Set<String> TURN_JSON = Set.of("figures_json", "expressions_json", "ledger_json", "verify_result");
    private static final Set<String> STEP_JSON = Set.of("tool_args", "tool_result");
    private static final Set<String> TOOL_JSON = Set.of("args");

    private final JdbcTemplate jdbc;
    private final String turnSql;
    private final String stepSql;
    private final String toolSql;

    public JdbcAgentEventFactWriter(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), true);
    }

    public JdbcAgentEventFactWriter(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    public JdbcAgentEventFactWriter(JdbcTemplate jdbc, boolean postgres) {
        this.jdbc = jdbc;
        this.turnSql = upsert("conversation_turn", TURN_COLUMNS, TURN_JSON, postgres);
        this.stepSql = upsert("agent_step", STEP_COLUMNS, STEP_JSON, postgres);
        this.toolSql = upsert("tool_call", TOOL_COLUMNS, TOOL_JSON, postgres);
    }

    @Override
    public int writeBatch(List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        Map<String, String> userText = new HashMap<>();
        Map<String, String> answer = new HashMap<>();
        Map<String, String> toolArgs = new HashMap<>();
        for (AgentEvent event : events) {
            // 用户输入有两个来源：平台发的 USER_MESSAGE（带正文）与运行时发的 TURN_START（骨架期是空载荷）。
            // 取「先到且非空者」：这样事件顺序不影响结果，空的运行时事件也冲不掉平台的正文。
            if (event.type() == com.djzy.assistant.spi.AgentEventType.USER_MESSAGE
                    || event.type() == com.djzy.assistant.spi.AgentEventType.TURN_START) {
                String text = str(event, "text", "userText", "userInput");
                if (!blank(text)) {
                    userText.putIfAbsent(event.turnId(), text);
                }
            } else if (event.type() == com.djzy.assistant.spi.AgentEventType.TEXT) {
                answer.put(event.turnId(), str(event, "text", "content"));
            } else if (event.type() == com.djzy.assistant.spi.AgentEventType.TOOL_CALL_END) {
                toolArgs.put(toolKey(event.turnId(), str(event, "toolName")), json(event, "arguments", "args"));
            }
        }
        int rows = 0;
        for (AgentEvent event : events) {
            rows += write(
                    event,
                    userText.get(event.turnId()),
                    answer.get(event.turnId()),
                    toolArgs.get(toolKey(event.turnId(), str(event, "toolName"))));
        }
        return rows;
    }

    private int write(AgentEvent event, String userInput, String fallbackAnswer, String callArgs) {
        Timestamp at = Timestamp.from(Instant.ofEpochMilli(event.timestampEpochMs()));
        return switch (event.type()) {
            case TURN_END -> turnRow(event, userInput, fallbackAnswer, at);
            case THOUGHT -> stepRow(event, "thought", str(event, "content", "text", "delta"), null, null, null, at);
            case TOOL_CALL_END ->
                stepRow(event, "action", null, str(event, "toolName"), json(event, "arguments", "args"), null, at);
            case TOOL_RESULT -> toolResultRows(event, callArgs, at);
            case ERROR -> stepRow(event, "observation", str(event, "message", "error"), null, null, null, at);
            default -> 0;
        };
    }

    /** 工具调用的身份：一轮里同一个工具可能被调多次，用 轮次 + 工具名 兜住常见情形。 */
    private static String toolKey(String turnId, String toolName) {
        return turnId + "|" + toolName;
    }

    private int turnRow(AgentEvent event, String userInput, String fallbackAnswer, Timestamp at) {
        if (blank(event.sessionId()) || blank(event.turnId()) || blank(event.traceId())) {
            return 0;
        }
        String answer = firstNonBlank(str(event, "finalAnswer", "answer"), fallbackAnswer);
        return jdbc.update(
                turnSql,
                event.eventId(),
                event.sessionId(),
                event.turnId(),
                event.traceId(),
                firstNonBlank(userInput, str(event, "userInput", "userText")),
                answer,
                json(event, "figures", "figuresJson"),
                json(event, "expressions", "expressionsJson"),
                json(event, "ledger", "ledgerJson"),
                json(event, "verifyResult", "verify"),
                longOrNull(event, "latencyMs", "latency"),
                at);
    }

    private int toolResultRows(AgentEvent event, String callArgs, Timestamp at) {
        int rows = stepRow(event, "observation", null, str(event, "toolName"), null, jsonPayload(event), at);
        if (blank(event.traceId()) || blank(event.turnId()) || blank(str(event, "toolName"))) {
            return rows;
        }
        rows += jdbc.update(
                toolSql,
                event.eventId(),
                event.traceId(),
                event.turnId(),
                str(event, "toolName"),
                // TOOL_RESULT 不带入参（入参在 TOOL_CALL_END 上），同批补上；补不上就留空
                firstNonBlank(json(event, "arguments", "args"), callArgs),
                resultSize(event),
                str(event, "status"),
                str(event, "error", "message"),
                longOrNull(event, "latencyMs", "latency"),
                at);
        return rows;
    }

    private int stepRow(
            AgentEvent event, String type, String content, String toolName, String toolArgs, String toolResult, Timestamp at) {
        if (blank(event.sessionId()) || blank(event.turnId()) || blank(event.traceId())) {
            return 0;
        }
        return jdbc.update(
                stepSql,
                event.eventId(),
                event.sessionId(),
                event.turnId(),
                event.traceId(),
                event.seq(),
                type,
                content,
                toolName,
                toolArgs,
                toolResult,
                at);
    }

    /** 工具结果大小：优先取结果体（{@code data} / {@code content}）的序列化长度。 */
    private static Long resultSize(AgentEvent event) {
        Object data = event.payload().get("data");
        if (data != null) {
            return (long) jsonValue(data).length();
        }
        Object content = event.payload().get("content");
        if (content instanceof String text) {
            return (long) text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return null;
    }

    private static String jsonPayload(AgentEvent event) {
        return jsonValue(event.payload());
    }

    private static String json(AgentEvent event, String... keys) {
        for (String key : keys) {
            Object value = event.payload().get(key);
            if (value != null) {
                return jsonValue(value);
            }
        }
        return null;
    }

    private static String jsonValue(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("事实表 JSON 序列化失败（不静默丢字段）", e);
        }
    }

    private static String str(AgentEvent event, String... keys) {
        for (String key : keys) {
            Object value = event.payload().get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
            if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static Long longOrNull(AgentEvent event, String... keys) {
        for (String key : keys) {
            Object value = event.payload().get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** PG 用 {@code ON CONFLICT DO NOTHING}，H2 用 {@code MERGE ... KEY}；列清单一处定义、两种方言共用。 */
    private static String upsert(String table, String columns, Set<String> jsonColumns, boolean postgres) {
        List<String> marks = new ArrayList<>();
        for (String column : columns.split(", ")) {
            marks.add(postgres && jsonColumns.contains(column) ? "?::jsonb" : "?");
        }
        String values = String.join(", ", marks);
        if (postgres) {
            return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values
                    + ") ON CONFLICT (event_id) DO NOTHING";
        }
        return "MERGE INTO " + table + " (" + columns + ") KEY (event_id) VALUES (" + values + ")";
    }

    /** 供文档/测试对照：事实表 + 它们承载的事件类型。 */
    public static Map<String, Set<String>> tableCoverage() {
        Map<String, Set<String>> coverage = new java.util.LinkedHashMap<>();
        coverage.put("conversation_turn", new LinkedHashSet<>(List.of("TURN_END", "USER_MESSAGE")));
        coverage.put("agent_step", new LinkedHashSet<>(List.of("THOUGHT", "TOOL_CALL_END", "TOOL_RESULT", "ERROR")));
        coverage.put("tool_call", new LinkedHashSet<>(List.of("TOOL_RESULT")));
        return coverage;
    }
}
