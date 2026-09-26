package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 历史投影（§19.4）：把框架的会话状态翻译成前端认的事件。
 *
 * <p>这条链取代了从前「平台自己记一份 SSE 台账」的做法——投影错了不会报错，
 * 只会让用户的历史缺一块或多一块，所以每条规则都单独钉一遍。
 */
class SessionTranscriptTest {

    @Test
    void 一轮回答拆成提问_正文_收尾() {
        List<SessionTranscript.TurnSlice> turns = SessionTranscript.turns(state(
                user("心内科门诊量"), assistant("上个月共 1200 人次。")));

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).events())
                .extracting(SessionTranscript.StreamEventView::name)
                .containsExactly("user", "token", "done");
        assertThat(turns.get(0).events().get(0).data()).containsEntry("text", "心内科门诊量");
        assertThat(turns.get(0).events().get(1).data()).containsEntry("delta", "上个月共 1200 人次。");
    }

    /**
     * 「本轮能力快照」是平台**送给模型**的第二块文本，不是用户说的话。
     *
     * <p>它如果漏进气泡，用户会看到一段自己从没打过的字——投影只取第一块，就是从这条规则来的。
     */
    @Test
    void 提问只取第一块文本_平台附的快照不进气泡() {
        Msg question = UserMessage.builder()
                .content(List.of(
                        TextBlock.builder().text("你有哪些能力").build(),
                        TextBlock.builder().text("\n(本轮能力快照：本轮可用接口 = iface_a。)").build()))
                .build();

        List<SessionTranscript.TurnSlice> turns = SessionTranscript.turns(state(question, assistant("能查门诊量。")));

        assertThat(turns.get(0).events().get(0).data()).containsEntry("text", "你有哪些能力");
    }

    @Test
    void 两轮各自成组_顺序与提问顺序一致() {
        List<SessionTranscript.TurnSlice> turns = SessionTranscript.turns(state(
                user("第一问"), assistant("一"), user("第二问"), assistant("二")));

        assertThat(turns).hasSize(2);
        assertThat(turns).extracting(SessionTranscript.TurnSlice::turnId).containsExactly("turn-0", "turn-1");
        assertThat(turns.get(1).events().get(0).data()).containsEntry("text", "第二问");
    }

    /**
     * HITL 确认续跑：平台会补一条**带确认结果元数据**的用户消息（正文是空的）。
     *
     * <p>它属于同一轮的后半段：当成新提问就会把一条回答劈成两半（前半段没有答案、后半段没有提问）。
     */
    @Test
    void 确认续跑并入同一轮() {
        Msg continuation = Msg.builderForRole(MsgRole.USER)
                .textContent("")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of()))
                .build();

        List<SessionTranscript.TurnSlice> turns = SessionTranscript.turns(state(
                user("登记这个查询"), assistant("需要你确认。"), continuation, assistant("已执行。")));

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).events())
                .extracting(SessionTranscript.StreamEventView::name)
                .containsExactly("user", "token", "token", "done");
    }

    @Test
    void 工具调用还原成卡片_思考单独走推理通道() {
        Msg withTool = Msg.builderForRole(MsgRole.ASSISTANT)
                .content(List.of(
                        ThinkingBlock.builder().thinking("先查一下权限").build(),
                        new ToolUseBlock("call-1", "iface_doctor_a", Map.of("month", "2026-08"))))
                .build();
        Msg toolResult = Msg.builderForRole(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.of("call-1", "iface_doctor_a", TextBlock.builder().text("1200").build())))
                .build();

        List<SessionTranscript.TurnSlice> turns =
                SessionTranscript.turns(state(user("查一下"), withTool, toolResult, assistant("1200 人次。")));

        List<SessionTranscript.StreamEventView> events = turns.get(0).events();
        assertThat(events).extracting(SessionTranscript.StreamEventView::name)
                .containsExactly("user", "step", "tool", "tool", "token", "done");
        // 思考走 step + reasoning：前端据此只放进「推理原文」，不落成一张卡
        assertThat(events.get(1).data()).containsEntry("type", "think").containsEntry("reasoning", "先查一下权限");
        assertThat(events.get(2).data()).containsEntry("toolName", "iface_doctor_a").containsEntry("phase", "start");
        assertThat(events.get(3).data()).containsEntry("phase", "result").containsEntry("resultSize", 4);
    }

    /**
     * 上下文不是从提问开始的（例如状态里只留了半轮）：照样要显示，别把它吞掉。
     *
     * <p>宁可让用户看到一段「没有提问的回答」，也好过整段消失——后者看起来就是数据丢了。
     */
    @Test
    void 没有提问的片段也照样回放() {
        List<SessionTranscript.TurnSlice> turns = SessionTranscript.turns(state(assistant("半句话")));

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).events())
                .extracting(SessionTranscript.StreamEventView::name)
                .containsExactly("user", "token", "done");
        assertThat(turns.get(0).events().get(0).data()).containsEntry("text", "");
    }

    @Test
    void 空状态回放出空列表() {
        assertThat(SessionTranscript.turns(null)).isEmpty();
        assertThat(SessionTranscript.turns(AgentState.builder().sessionId("s1").build())).isEmpty();
    }

    /**
     * 末尾那一轮「开了头但没跑完」的两种样子必须长得不一样（T1-08 / T1-09）。
     *
     * <p>前端只看事件：**没有 done** 就显示成进行中（并接着去接流等结果），
     * **有 error + done** 才显示成「没跑完，请重发」。两种混成一种，用户就会看到
     * 一个永远转圈的气泡，或者一个正在跑的轮次被误报成失败。
     */
    @Test
    void 没跑完的轮次_还在跑与已中断的形状不同() {
        AgentState state = state(user("心内科门诊量"), assistant("上个月 1200 人次。"));
        PlatformLiveTurnState live = new PlatformLiveTurnState("turn-live", "instance-a", "急诊科留观人数", 1L);

        List<SessionTranscript.TurnSlice> running =
                SessionTranscript.turns(state, live, SessionTranscript.Trailing.RUNNING);
        assertThat(running).hasSize(2);
        assertThat(running.get(1).events())
                .extracting(SessionTranscript.StreamEventView::name)
                .containsExactly("user");

        List<SessionTranscript.TurnSlice> interrupted =
                SessionTranscript.turns(state, live, SessionTranscript.Trailing.INTERRUPTED);
        assertThat(interrupted).hasSize(2);
        assertThat(interrupted.get(1).events())
                .extracting(SessionTranscript.StreamEventView::name)
                .containsExactly("user", "error", "done");

        // 两种「没有这一轮」的入参都不该凭空多出一条
        assertThat(SessionTranscript.turns(state, live, SessionTranscript.Trailing.NONE)).hasSize(1);
        assertThat(SessionTranscript.turns(state, null, SessionTranscript.Trailing.RUNNING)).hasSize(1);
    }

    /** 投影出来的 seq 是「第几条」：前端靠它排序，必须从 0 起、连续、不重复。 */
    @Test
    void 每条事件都有连续且唯一的_seq() {
        List<SessionTranscript.StreamEventView> events = SessionTranscript.turns(
                        state(user("问"), assistant("答")))
                .get(0)
                .events();

        assertThat(events).extracting(SessionTranscript.StreamEventView::seq).containsExactly(0L, 1L, 2L);
    }

    private static AgentState state(Msg... messages) {
        return AgentState.builder().sessionId("s-1").userId("alice").context(List.of(messages)).build();
    }

    private static Msg user(String text) {
        return new UserMessage(text);
    }

    private static Msg assistant(String text) {
        return Msg.builderForRole(MsgRole.ASSISTANT).textContent(text).build();
    }
}
