package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 框架 typed event → 中立事件（ADR-32 ②）。
 *
 * <p>这里的断言都对应下游的硬要求：中立事件是落库与 SSE 的**共同输入**，
 * 它长歪一点，事实表和界面就各歪一处。
 */
class AgentscopeEventTranslatorTest {

    /** 一轮的终点只许有一个：多一个 done 会多推一次，多一个 TURN_END 会让 conversation_turn 多一行。 */
    @Test
    void 结果与结束事件都是终点_但只放行第一个() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(
                translator,
                new AgentResultEvent(Msg.builder().textContent("心内科 1286 人次").build()),
                new AgentEndEvent("reply-1"));

        assertEquals(List.of(AgentEventType.TURN_END), types(events));
    }

    @Test
    void 顺序反过来也只放行一个终点() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(
                translator, new AgentEndEvent("reply-1"), new AgentResultEvent(Msg.builder().textContent("答").build()));

        assertEquals(List.of(AgentEventType.TURN_END), types(events));
    }

    /** 只发结束、不发结果的路径也要收尾——否则这一轮在事实表里没有行。 */
    @Test
    void 只有结束事件时照样收尾() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(translator, new AgentEndEvent("reply-1"));

        assertEquals(List.of(AgentEventType.TURN_END), types(events));
    }

    /** 被丢掉的那个终点不占序号：TCK-9 要求 seq 严格递增，跳号是允许的，重叠不是。 */
    @Test
    void 序号严格递增_且丢掉终点不占号() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(
                translator,
                new AgentStartEvent("s-1", "reply-1", "doctor-data-assistant"),
                new AgentResultEvent(Msg.builder().textContent("答").build()),
                new AgentEndEvent("reply-1"),
                new AgentResultEvent(Msg.builder().textContent("又答一次").build()));

        assertEquals(List.of(AgentEventType.TURN_START, AgentEventType.TURN_END), types(events));
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).seq() > events.get(i - 1).seq(), "seq 必须严格递增");
        }
    }

    /**
     * 工具调用的三段（开始 / 参数增量 / 结束）要能拼出「调的是谁、用什么参数」。
     *
     * <p>实测缺口：`agent_step` 里 TOOL_CALL_END 落的 action 行 `tool_name` 为空、只有 `tool_args`，
     * 审计上就成了「有入参、不知道调的是谁」——只能靠紧随其后的 observation 行反推。
     * 原因是翻译结束事件时只放了攒齐的 arguments，没放工具名。这条用例守的就是两样都在。
     */
    @Test
    void 工具调用结束事件同时带上工具名与攒齐的参数() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(
                translator,
                new ToolCallStartEvent("reply-1", "call-1", "iface_doctor_list"),
                new ToolCallDeltaEvent("reply-1", "call-1", "iface_doctor_list", "{\"dept_code\":"),
                new ToolCallDeltaEvent("reply-1", "call-1", "iface_doctor_list", "\"呼吸科\"}"),
                new ToolCallEndEvent("reply-1", "call-1", "iface_doctor_list"));

        assertEquals(
                List.of(
                        AgentEventType.TOOL_CALL_START,
                        AgentEventType.TOOL_CALL_ARGS_DELTA,
                        AgentEventType.TOOL_CALL_ARGS_DELTA,
                        AgentEventType.TOOL_CALL_END),
                types(events));
        AgentEvent end = events.stream()
                .filter(event -> event.type() == AgentEventType.TOOL_CALL_END)
                .findFirst()
                .orElseThrow();
        assertEquals("iface_doctor_list", end.payload().get("toolName"));
        assertEquals("{\"dept_code\":\"呼吸科\"}", end.payload().get("arguments"));
    }

    /**
     * 回答要跟着终点一起走（§8.3 / ADR-28）。
     *
     * <p>实测缺口：事实表的 {@code final_answer} 128 轮里有 25 轮是空的。原因不是模型没答，
     * 而是答案落在块结束时的 {@code TEXT} 事件上，与 {@code TURN_END} 之间隔着几百条增量，
     * 消费端攒批写入时两者常常分进两个批次——而 {@code TEXT} 自己不写 {@code conversation_turn}，
     * 那一轮的回答就永久丢了。终点带上正文，落库就不再取决于批次边界。
     */
    @Test
    void 终点事件带上整轮回答正文() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(
                translator,
                new TextBlockDeltaEvent("reply-1", "block-1", "心内科"),
                new TextBlockDeltaEvent("reply-1", "block-1", " 1286 人次"),
                new TextBlockEndEvent("reply-1", "block-1"),
                new AgentEndEvent("reply-1"));

        AgentEvent end = events.get(events.size() - 1);
        assertEquals(AgentEventType.TURN_END, end.type());
        assertEquals("心内科 1286 人次", end.payload().get("finalAnswer"));
    }

    /** 一个字都没吐的一轮（比如上来就挂起等确认）不该凭空补个空答案，交给消费端的兜底。 */
    @Test
    void 没有正文时终点不带空答案() {
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();

        List<AgentEvent> events = translate(translator, new AgentEndEvent("reply-1"));

        assertEquals(List.of(AgentEventType.TURN_END), types(events));
        assertTrue(!events.get(0).payload().containsKey("finalAnswer"), "空正文不该占着 finalAnswer");
    }

    private static List<AgentEventType> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::type).toList();
    }

    private static List<AgentEvent> translate(AgentscopeEventTranslator translator, io.agentscope.core.event.AgentEvent... raw) {
        List<AgentEvent> events = new ArrayList<>();
        for (io.agentscope.core.event.AgentEvent event : raw) {
            Optional<AgentEvent> translated = translator.translate(event, "s-1", "t-1", "tr-1");
            translated.ifPresent(events::add);
        }
        return events;
    }
}