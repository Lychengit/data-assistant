package com.djzy.assistant.runtime.agentscope;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 框架 typed event → 中立 {@link AgentEvent} 的翻译表（ADR-27 ② / ADR-32 ②）。
 *
 * <p>硬约束：**框架专有事件类型不得直接进入落库与 SSE**。这张表是换运行时唯一需要重写的东西之一
 * （另一个是 adapter 本身）。
 */
final class AgentscopeEventTranslator {

    private long seq;

    /** 终点事件是否已经放过（一轮只许有一个终点，见 {@link #mapType}）。 */
    private boolean terminalEmitted;

    /** 工具入参的增量片段：框架按片下发，得攒齐才是一个可读的 JSON。 */
    private final Map<String, StringBuilder> toolArguments = new LinkedHashMap<>();

    /** 文本块的增量正文：{@code TEXT_BLOCK_END} 只带块 id、不带正文，正文得自己攒。 */
    private final Map<String, StringBuilder> textBlocks = new LinkedHashMap<>();

    /**
     * 整轮回答的正文（所有文本块按顺序拼起来）。
     *
     * <p>为什么得单独攒一份、不能只在块结束时取：终点事件要把答案一起带走（见 {@link #translate}），
     * 而 {@link #takeText} 是**取走即释放**的，到 TURN_END 时各块早已被清空。
     */
    private final StringBuilder answer = new StringBuilder();

    Optional<AgentEvent> translate(
            io.agentscope.core.event.AgentEvent event, String sessionId, String turnId, String traceId) {
        AgentEventType type = mapType(event);
        if (type == null) {
            return Optional.empty();
        }
        if (type == AgentEventType.TURN_END) {
            // 重复的终点不是信息，是框架的收尾噪声：放行两个会让一轮在事实表里落两行
            // （conversation_turn 的粒度就是一轮）、在 SSE 上多推一个 done。
            if (terminalEmitted) {
                return Optional.empty();
            }
            terminalEmitted = true;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.putAll(event.getMetadata() == null ? Map.of() : event.getMetadata());
        if (event instanceof TextBlockDeltaEvent e) {
            payload.put("delta", e.getDelta());
            appendText(e.getBlockId(), e.getDelta());
            answer.append(e.getDelta());
        } else if (event instanceof TextBlockEndEvent e) {
            payload.put("text", takeText(e.getBlockId()));
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            payload.put("delta", e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            payload.put("toolName", e.getToolCallName());
            payload.put("toolCallId", e.getToolCallId());
        } else if (event instanceof ToolCallDeltaEvent e) {
            appendToolArguments(e.getToolCallId(), e.getDelta());
        } else if (event instanceof ToolCallEndEvent e) {
            // 事实表把入参记在 TOOL_CALL_END 上（§8.3 tool_call.args）：此刻参数已吐完
            payload.put("arguments", peekToolArguments(e.getToolCallId()));
            // 工具名也要带上：`agent_step` 的 action 行取的是这个字段，漏了就变成「有入参、不知道调的是谁」——
            // 实测 admin 那一轮 action 行 tool_name 为空，只能从紧随其后的 observation 行反推。
            payload.put("toolName", e.getToolCallName());
        } else if (event instanceof ToolResultEndEvent e) {
            payload.put("toolName", e.getToolCallName());
            payload.put("toolCallId", e.getToolCallId());
            payload.put("status", e.getState() == null ? "ok" : e.getState().name().toLowerCase(java.util.Locale.ROOT));
            // 结果事件也带一份入参：SSE 的 tool 卡只投影结果事件，不带就没得显示（§12.2）
            payload.put("arguments", takeToolArguments(e.getToolCallId()));
        } else if (event instanceof RequireUserConfirmEvent e) {
            payload.put(
                    "toolCalls",
                    e.getToolCalls() == null
                            ? java.util.List.of()
                            : e.getToolCalls().stream()
                                    .map(t -> Map.of("id", t.getId(), "name", t.getName(), "input", t.getInput()))
                                    .toList());
        } else if (event instanceof ExceedMaxItersEvent e) {
            payload.put("maxIters", e.getMaxIters());
            payload.put("currentIter", e.getCurrentIter());
            payload.put("code", "MAX_ITERS_EXCEEDED");
        }
        if (type == AgentEventType.TURN_END) {
            // 回答跟终点一起走，理由与「入参记在 TOOL_CALL_END」完全相同：
            // 事实表由消费端**攒批**写入，一条 TEXT 和它那一轮的 TURN_END 完全可能落进两个批次
            // （回答要跑好几秒，攒批间隔只有 100ms）。实测库里 128 轮有 25 轮 final_answer 为空——
            // 那 25 轮的回答只存在于上一个批次的 TEXT 事件里，而 TEXT 自己不写 conversation_turn，答案就永久丢了。
            // 挂到终点上，落库就不再取决于批次边界。
            String full = answer.toString();
            if (!full.isBlank()) {
                payload.putIfAbsent("finalAnswer", full);
            }
        }
        return Optional.of(AgentEvent.builder(type)
                .eventId(event.getId())
                .session(sessionId)
                .turn(turnId)
                .trace(traceId)
                .seq(seq++)
                .source(event.getSource() == null ? "main" : event.getSource())
                .putAll(payload)
                .build());
    }

    /**
     * 框架事件类型 → 中立类型。
     *
     * <p>{@code AGENT_RESULT} 与 {@code AGENT_END} 都映射成 {@link AgentEventType#TURN_END}：
     * 单看任何一个都可能是这一轮的终点（有的路径只发结果，有的只发结束），漏掉一个就会让这一轮
     * 在事实表里没有行、在 SSE 上永远不闭合。代价是**一轮可能来两个终点**，
     * 所以 {@link #translate} 只放行第一个。
     */
    /** 攒工具入参分片；{@code toolCallId} 为空时无法归并，直接丢弃而不是凑一个错位的 JSON。 */
    private void appendToolArguments(String toolCallId, String delta) {
        if (toolCallId == null || delta == null) {
            return;
        }
        toolArguments.computeIfAbsent(toolCallId, key -> new StringBuilder()).append(delta);
    }

    /** 看一眼入参（不清空）：END 之后还有结果事件要带同一份参数。 */
    private String peekToolArguments(String toolCallId) {
        StringBuilder args = toolCallId == null ? null : toolArguments.get(toolCallId);
        return args == null ? "" : args.toString();
    }

    /** 取走入参并释放：结果事件是这次调用的最后一站。 */
    private String takeToolArguments(String toolCallId) {
        StringBuilder args = toolCallId == null ? null : toolArguments.remove(toolCallId);
        return args == null ? "" : args.toString();
    }
    /**
     * 攒文本块正文。
     *
     * <p>为什么必须自己攒：框架的 {@code TEXT_BLOCK_END} 只给块 id，不给正文，而下游
     * （步骤卡 / 事实表 / 历史回放）要的是「这一块说了什么」。原先这里塞的是 replyId，
     * 于是每轮末尾都多出一张内容是 UUID 的步骤卡——既占视线，也让回放看不出说了什么。
     */
    private void appendText(String blockId, String delta) {
        if (blockId == null || delta == null) {
            return;
        }
        textBlocks.computeIfAbsent(blockId, key -> new StringBuilder()).append(delta);
    }

    /** 取走整块正文并释放：块结束就不再需要，留着只会随会话增长。 */
    private String takeText(String blockId) {
        StringBuilder text = blockId == null ? null : textBlocks.remove(blockId);
        return text == null ? "" : text.toString();
    }
    private static AgentEventType mapType(io.agentscope.core.event.AgentEvent event) {
        return switch (event.getType()) {
            case AGENT_START -> AgentEventType.TURN_START;
            case AGENT_END, AGENT_RESULT -> AgentEventType.TURN_END;
            case TEXT_BLOCK_DELTA -> AgentEventType.TEXT_DELTA;
            case TEXT_BLOCK_END -> AgentEventType.TEXT;
            case THINKING_BLOCK_DELTA -> AgentEventType.THOUGHT_DELTA;
            case THINKING_BLOCK_END -> AgentEventType.THOUGHT;
            case TOOL_CALL_START -> AgentEventType.TOOL_CALL_START;
            case TOOL_CALL_DELTA -> AgentEventType.TOOL_CALL_ARGS_DELTA;
            case TOOL_CALL_END -> AgentEventType.TOOL_CALL_END;
            case TOOL_RESULT_END -> AgentEventType.TOOL_RESULT;
            case REQUIRE_USER_CONFIRM -> AgentEventType.AWAITING_CONFIRM;
            case USER_CONFIRM_RESULT -> AgentEventType.CONFIRM_RESULT;
            case REQUIRE_EXTERNAL_EXECUTION -> AgentEventType.AWAITING_EXTERNAL_EXECUTION;
            case EXTERNAL_EXECUTION_RESULT -> AgentEventType.EXTERNAL_EXECUTION_RESULT;
            case SUBAGENT_EXPOSED -> AgentEventType.SUBAGENT_START;
            case ALL_TOOLS_DENIED -> AgentEventType.ALL_TOOLS_DENIED;
            case EXCEED_MAX_ITERS -> AgentEventType.EXCEED_MAX_ITERS;
            case REQUEST_STOP -> AgentEventType.REQUEST_STOP;
            case CUSTOM -> AgentEventType.CUSTOM;
            default -> null;
        };
    }
}
