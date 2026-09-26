package com.djzy.assistant.agentweb.session;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把框架的会话状态（{@link AgentState}）**投影**成前端认的事件形状（§19.4 历史会话）。
 *
 * <p>**为什么历史不再自己记一份、而是现算**：「这个会话聊过什么」这件事，框架状态里已经有一份
 * 完整且权威的记录（它就是送给模型的那串消息）。再自己记一份 SSE 台账，等于同一件事有两个真相：
 * 一份写了、另一份没写的时候，用户看到的历史和模型记得的上下文就会不一致，而且**没人会发现**。
 * 现在只有一条链：模型看到什么，历史就显示什么。
 *
 * <p>投影出来的事件**沿用实时流的同一套形状**（§12.2），所以前端用同一个归约器渲染
 * （{@code web/src/lib/turn.ts} 的 {@code applyEvent}）：历史会话和刚答完的那一轮长得一模一样。
 *
 * <p>**投影是有损的**，这里如实说明丢了什么、为什么可以丢：
 * <ul>
 *   <li>逐字流（token）还原成整段文本：状态里存的是消息，不是每个字的到达时刻；</li>
 *   <li>意图 / 权限这类「过程提示」不重放：它们描述的是当时那一次执行，不是对话内容；</li>
 *   <li>工具调用只还原「调了哪个、参数、结果多大」：结果正文本来就不进模型上下文（会被截断）。</li>
 * </ul>
 * 需要逐字回放、需要每一条过程事件时，走的是平台的事件事实表（§8.3 审计），不是这条历史链。
 */
public final class SessionTranscript {

    /**
     * 「这一轮没跑完」的错误码与文案。
     *
     * <p>两条路都要用它：读历史时补一条中断记录（{@link #turns(AgentState, PlatformLiveTurnState)}），
     * 以及「跨实例等待」等到一半发现那一轮已经没人管了（{@code CrossInstanceTurnRelay}）。
     * 收在这里是为了两处说的是**同一句话**——同一个故障在历史里和实时流里说法不一致，
     * 用户会以为是两件事。
     */
    public static final String TURN_INTERRUPTED_CODE = "TURN_INTERRUPTED";

    public static final String TURN_INTERRUPTED_MESSAGE =
            "上一轮没有跑完（服务中断或超时），已经产出的问题保留在这里，请重新发送一次。";

    private SessionTranscript() {}

    /**
     * @param turnId 一轮的编号；前端拿它当列表 key，必须唯一
     * @param events 按 seq 升序的事件，形状与 SSE 一致（{@code {seq, name, data}}），
     *     因此前端可以直接喂给 {@code applyEvent}
     */
    public record TurnSlice(String turnId, List<StreamEventView> events) {}

    /** 一条事件的线上形态：与 SSE 的 {@code id:} / {@code event:} / {@code data:} 一一对应。 */
    public record StreamEventView(long seq, String name, Map<String, Object> data) {}

    /**
     * 历史末尾那一轮「开了头但还没跑完」的两种样子（T1-08 / T1-09）。
     *
     * <p>为什么要区分：同样是「没跑完」，对用户的意义完全不同——
     * **还在跑**的时候要多显示一个「进行中」的轮次（问题先亮出来，答案稍后接上），
     * **已经没人跑**的时候要给一句「没跑完，请重发」。混成一种，要么把正在跑的误报成失败，
     * 要么把已经断的挂成一个永远转圈的气泡。
     */
    public enum Trailing {
        /** 没有没跑完的轮次。 */
        NONE,
        /** 还在别的实例上跑：只显示问题，**不给**结束标记（前端据此接上去等结果）。 */
        RUNNING,
        /** 已经没人跑了（实例硬挂 / 超时）：给一条错误说明，并把这一轮闭合。 */
        INTERRUPTED
    }

    /** 把整个会话的上下文切成「一轮一轮」。{@code state} 为空（新会话 / 已删）时返回空列表。 */
    public static List<TurnSlice> turns(AgentState state) {
        return turns(state, null, Trailing.NONE);
    }

    /**
     * 同上，另外补上「开始了但没跑完」的那一轮（T1-08）。
     *
     * <p>为什么这一轮不在 {@code state} 里：框架只在**一轮结束时**才把会话状态写进库，
     * 所以实例被硬杀时那一轮什么都没留下。平台在开跑前另行写了一个开始标记，
     * 调用方确认「标记还在、但没人占着坑位」之后传进来，这里把它补成一条**跟正常轮次长得一样**的
     * 记录：一条用户提问 + 一条 error + done。于是前端不用改代码，用户看到的就是
     * 「我确实问过，但这一轮没跑完，请重新发送」——而不是历史里凭空少了一轮。
     *
     * @param live 末尾那一轮的开始标记；{@code null} = 没有没跑完的轮次
     * @param trailing 这一轮该显示成什么样子（见 {@link Trailing}）
     */
    public static List<TurnSlice> turns(AgentState state, PlatformLiveTurnState live, Trailing trailing) {
        if (state == null) {
            return List.of();
        }
        List<TurnSlice> turns = new ArrayList<>();
        List<StreamEventView> events = null;
        for (Msg message : state.getContext()) {
            if (isNewQuestion(message)) {
                if (events != null) {
                    turns.add(new TurnSlice(turnId(turns.size()), withDone(turnId(turns.size()), events)));
                }
                events = new ArrayList<>();
                push(events, "user", Map.of("text", questionOf(message), "turnId", turnId(turns.size())));
                continue;
            }
            if (events == null) {
                // 上下文不是从提问开始的（例如运行中途留下的片段）：照样要显示，别把它吞掉
                events = new ArrayList<>();
                push(events, "user", Map.of("text", "", "turnId", turnId(turns.size())));
            }
            emitMessage(events, message);
        }
        if (events != null) {
            turns.add(new TurnSlice(turnId(turns.size()), withDone(turnId(turns.size()), events)));
        }
        if (live != null && trailing != Trailing.NONE) {
            String turnId = turnId(turns.size());
            List<StreamEventView> pending = new ArrayList<>();
            String text = live.getText() == null ? "" : live.getText();
            push(pending, "user", Map.of("text", text, "turnId", turnId));
            if (trailing == Trailing.INTERRUPTED) {
                push(pending, "error", data("code", TURN_INTERRUPTED_CODE, "message", TURN_INTERRUPTED_MESSAGE));
                turns.add(new TurnSlice(turnId, withDone(turnId, pending)));
            } else {
                // 还在跑：**不给 done**。前端看到「没有结束标记的轮次」就知道它还没完，
                // 会接着去接流等结果（ChatView.openSession），而不是把它当成一轮跑完的对话。
                turns.add(new TurnSlice(turnId, List.copyOf(pending)));
            }
        }
        return List.copyOf(turns);
    }

    /**
     * 这一条消息是不是「用户又问了新的一句」。
     *
     * <p>要排掉两类：HITL 确认续跑时平台自己补的那条（带确认结果的元数据），
     * 它属于**同一轮**回答的后半段；以及正文为空的脏消息。
     */
    static boolean isNewQuestion(Msg message) {
        return message.getRole() == MsgRole.USER
                && !message.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS)
                && !questionOf(message).isBlank();
    }

    /**
     * 用户那一句的正文。
     *
     * <p>只取**第一个文本块**：平台会把「本轮能力快照」作为第二个文本块附在提问之后送进模型
     * （见 {@code AgentscopeRuntimeAdapter#userMessage}），那是给模型看的提示、不是用户说的话，
     * 显示到气泡里就是数据污染。
     */
    static String questionOf(Msg message) {
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock text) {
                return text.getText() == null ? "" : text.getText();
            }
        }
        return "";
    }

    private static void emitMessage(List<StreamEventView> events, Msg message) {
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ThinkingBlock thinking) {
                push(events, "step", data("type", "think", "reasoning", thinking.getThinking()));
            } else if (block instanceof TextBlock text && message.getRole() == MsgRole.ASSISTANT) {
                // 助手说的话就是答案正文：还原成一个整段的 token 事件（前端直接往上贴）
                push(events, "token", data("delta", text.getText()));
            } else if (block instanceof ToolUseBlock use) {
                push(events, "tool", data(
                        "toolName", use.getName(),
                        "args", use.getInput() == null ? Map.of() : use.getInput(),
                        "phase", "start"));
            } else if (block instanceof ToolResultBlock result) {
                push(events, "tool", data(
                        "toolName", result.getName(),
                        "args", "",
                        "resultSize", outputLength(result),
                        "status", result.isSuspended() ? "suspended" : "ok",
                        "phase", "result"));
            }
        }
    }

    /** 工具结果的「多大」：只报字符数，正文不往历史里塞（正文可能很大，且模型也只看到截断版）。 */
    private static int outputLength(ToolResultBlock result) {
        int size = 0;
        for (ContentBlock output : result.getOutput()) {
            if (output instanceof TextBlock text && text.getText() != null) {
                size += text.getText().length();
            }
        }
        return size;
    }

    private static String turnId(int index) {
        return "turn-" + index;
    }

    /** {@code seq} 在投影里就是「第几条」：只用来排序，和实时流的 seq 不是同一套编号（见 SessionCatalog 的说明）。 */
    private static void push(List<StreamEventView> events, String name, Map<String, Object> data) {
        events.add(new StreamEventView(events.size(), name, data));
    }

    /** 每轮都以 {@code done} 收尾：前端靠它把这轮标成「已结束」，否则气泡会一直转圈。 */
    private static List<StreamEventView> withDone(String turnId, List<StreamEventView> events) {
        List<StreamEventView> closed = new ArrayList<>(events);
        push(closed, "done", Map.of("turnId", turnId));
        return closed;
    }

    private static Map<String, Object> data(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
