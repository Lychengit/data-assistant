package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 事件 → 事实表（§19.6 / §8.3）：落点、幂等、同批补齐。 */
class JdbcAgentEventFactWriterTest {

    private static AgentEvent.Builder base(AgentEventType type, String eventId, long seq) {
        return AgentEvent.builder(type).eventId(eventId).session("s-1").turn("t-1").trace("tr-1").seq(seq);
    }

    @Test
    void 一轮对话落到三张事实表_其余事件只留在日志里() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcAgentEventFactWriter writer = new JdbcAgentEventFactWriter(jdbc, false);

        int rows = writer.writeBatch(List.of(
                base(AgentEventType.TURN_START, "e-0", 0).put("userText", "心内科上个月门诊量").build(),
                base(AgentEventType.TEXT_DELTA, "e-1", 1).put("delta", "已完成").build(),
                base(AgentEventType.THOUGHT, "e-2", 2).put("content", "先看权限再看数据").build(),
                base(AgentEventType.TOOL_CALL_END, "e-3", 3)
                        .put("toolName", "iface_doctor_performance")
                        .put("arguments", Map.of("month", "2026-08"))
                        .build(),
                base(AgentEventType.TOOL_RESULT, "e-4", 4)
                        .put("toolName", "iface_doctor_performance")
                        .put("status", "ok")
                        .put("data", Map.of("rows", List.of(Map.of("doctorId", "d-1"))))
                        .put("latencyMs", 120)
                        .build(),
                base(AgentEventType.TEXT, "e-5", 5).put("text", "已完成").build(),
                base(AgentEventType.TURN_END, "e-6", 6).build(),
                // 没有事实表落点的事件：只活在本地日志
                base(AgentEventType.AWAITING_CONFIRM, "e-7", 7).build()));

        // TURN_END 1 行 + THOUGHT 1 行 + TOOL_CALL_END 1 行 + TOOL_RESULT 2 行（observation + 台账）= 5
        assertEquals(5, rows);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM conversation_turn", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM agent_step", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tool_call", Integer.class));

        Map<String, Object> turn = jdbc.queryForMap("SELECT * FROM conversation_turn");
        assertEquals("t-1", turn.get("turn_id"));
        assertEquals("心内科上个月门诊量", turn.get("user_input"));
        assertEquals("已完成", turn.get("final_answer"));

        Map<String, Object> tool = jdbc.queryForMap("SELECT * FROM tool_call");
        assertEquals("iface_doctor_performance", tool.get("tool_name"));
        assertEquals("ok", tool.get("status"));
        assertEquals(120L, ((Number) tool.get("latency_ms")).longValue());
        assertEquals("{\"month\":\"2026-08\"}", tool.get("args"));
    }

    @Test
    void 同一事件重复投递不写重_at_least_once下不重不漏() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcAgentEventFactWriter writer = new JdbcAgentEventFactWriter(jdbc, false);
        List<AgentEvent> batch = List.of(
                base(AgentEventType.THOUGHT, "e-1", 1).put("content", "先看权限").build(),
                base(AgentEventType.TURN_END, "e-2", 2).build());

        writer.writeBatch(batch);
        writer.writeBatch(batch);
        writer.writeBatch(batch);

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM conversation_turn", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_step", Integer.class));
    }

    /**
     * 攒批边界不该决定 {@code user_input} 有没有值。
     *
     * <p>真实情形就是这样：一轮回答要跑好几秒，而消费端攒批间隔只有 100ms，
     * 用户提问那一条早在前一批被搬走了，终点事件单独成批。提问必须跟着终点一起走，
     * 否则查询视图里的这一轮只剩答案、没有提问（回放与评估都重建不出来）。
     */
    @Test
    void 终点事件自带提问_与用户提问分属两批也落得上() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcAgentEventFactWriter writer = new JdbcAgentEventFactWriter(jdbc, false);

        writer.writeBatch(
                List.of(base(AgentEventType.USER_MESSAGE, "e-0", 0).put("text", "心内科门诊量").build()));
        writer.writeBatch(List.of(base(AgentEventType.TURN_END, "e-1", 1)
                .put("userText", "心内科门诊量")
                .put("finalAnswer", "共 1286 人次")
                .build()));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM conversation_turn");
        assertEquals("心内科门诊量", row.get("user_input"));
        assertEquals("共 1286 人次", row.get("final_answer"));
    }

    @Test
    void 缺必填列的事件跳过而不是报错_它本来就只属于日志() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcAgentEventFactWriter writer = new JdbcAgentEventFactWriter(jdbc, false);

        AgentEvent noSession = AgentEvent.builder(AgentEventType.TURN_END).eventId("e-1").turn("t-1").trace("tr-1").build();
        AgentEvent noToolName = base(AgentEventType.TOOL_RESULT, "e-2", 1).put("status", "ok").build();

        writer.writeBatch(List.of(noSession, noToolName));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM conversation_turn", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_step", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tool_call", Integer.class));
    }

    @Test
    void 台账字段按列落库_回放时才读得出来() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcAgentEventFactWriter writer = new JdbcAgentEventFactWriter(jdbc, false);

        writer.writeBatch(List.of(base(AgentEventType.TURN_END, "e-1", 1)
                .put("finalAnswer", "心内科共 1286 人次")
                .put("figures", List.of(Map.of("value", 1286)))
                .put("ledger", List.of(Map.of("expression", "1286")))
                .put("verifyResult", Map.of("outcome", "pass"))
                .put("latencyMs", 842)
                .build()));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM conversation_turn");
        assertEquals("心内科共 1286 人次", row.get("final_answer"));
        assertEquals("842", String.valueOf(((Number) row.get("latency_ms")).longValue()));
        assertEquals("[{\"value\":1286}]", row.get("figures_json"));
        assertEquals("{\"outcome\":\"pass\"}", row.get("verify_result"));
        assertNull(row.get("user_input"));
    }
}
