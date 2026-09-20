package com.djzy.assistant.runtime.noop;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.RuntimeCapabilities;
import com.djzy.assistant.spi.RuntimeMismatchException;
import com.djzy.assistant.spi.Snapshot;
import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvocationStatus;
import com.djzy.assistant.spi.tool.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * 假实现（§18.11.1）：不调模型、不出网，行为完全由输入文本决定，用于契约回归（TCK）。
 *
 * <p>它同时是「新运行时实现」的对照物：任何新实现都必须跑通与它相同的 TCK 用例才能上线（§16-9）。
 */
public final class NoopRuntimeAdapter implements AgentRuntimePort {

    public static final String RUNTIME_ID = "noop";
    public static final String RUNTIME_VERSION = "1.0.0";

    @Override
    public String id() {
        return RUNTIME_ID;
    }

    @Override
    public String version() {
        return RUNTIME_VERSION;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, true, true, true, false, false, true);
    }

    @Override
    public AgentSession start(AgentRunRequest request) {
        return new NoopSession(request.sessionId(), RUNTIME_ID, RUNTIME_VERSION, request);
    }

    @Override
    public Flux<AgentEvent> stream(AgentSession session, AgentTurn turn) {
        NoopSession noop = (NoopSession) session;
        if (noop.canceled) {
            return Flux.empty();
        }
        String text = turn.userText() == null ? "" : turn.userText();
        List<AgentEvent> events = new ArrayList<>();
        NoopEventSink sink = new NoopEventSink(session, turn, events);
        if (noop.pendingTool != null) {
            // 挂起后的续跑：确认结果已在 confirm() 中暂存（§19.9 HITL 往返）。
            return continueAfterConfirm(noop, turn, sink, events);
        }
        sink.emit(AgentEventType.TURN_START, Map.of("userText", text));
        if (text.contains("停止") || text.contains("stop")) {
            sink.emit(AgentEventType.REQUEST_STOP, Map.of());
            sink.emit(AgentEventType.TURN_END, Map.of());
            return Flux.fromIterable(events);
        }
        if (text.contains("死循环") || text.contains("loop")) {
            sink.emit(AgentEventType.EXCEED_MAX_ITERS, Map.of("maxIters", noop.request.maxIters()));
            sink.emit(AgentEventType.TURN_END, Map.of());
            return Flux.fromIterable(events);
        }
        ToolSpec tool = noop.request.tools().all().stream().findFirst().orElse(null);
        if (tool != null && (text.contains("查询") || text.contains("tool") || text.contains("data"))) {
            List<?> denied = (List<?>) noop.request.attributes().getOrDefault("deniedTools", List.of());
            if (denied.contains(tool.name())) {
                // 框架权限规则（DENY）在运行时就拦下：**不产生任何 ToolInvoker 调用**（TCK-1）。
                sink.emit(AgentEventType.ALL_TOOLS_DENIED, Map.of("toolName", tool.name()));
                sink.emit(AgentEventType.TEXT, Map.of("text", "无可用工具"));
                sink.emit(AgentEventType.TURN_END, Map.of());
                return Flux.fromIterable(events);
            }
            sink.emit(AgentEventType.TOOL_CALL_START, Map.of("toolName", tool.name(), "arguments", Map.of("month", "2026-08")));
            sink.emit(AgentEventType.TOOL_CALL_END, Map.of("toolName", tool.name()));
            ToolInvocationResult result = invoke(noop, turn, tool, Map.of("month", "2026-08"));
            sink.toolResult(tool.name(), result);
            if (result.status() == ToolInvocationStatus.AWAITING_CONFIRM) {
                noop.pendingConfirmId = String.valueOf(result.meta().get("confirmId"));
                noop.pendingTool = tool.name();
                noop.pendingArguments = Map.of("month", "2026-08");
                // HITL（§19.9）：先把「需要确认」按中立事件上报（投影成对话气泡里的确认卡），
                // 再用 TURN_END{suspended=true} 收尾——挂起不是异常，是这一轮的正常终点。
                sink.emit(AgentEventType.AWAITING_CONFIRM, Map.of(
                        "confirmId", noop.pendingConfirmId,
                        "action", tool.name(),
                        "summary", result.content() == null ? "" : result.content()));
                sink.emit(AgentEventType.TURN_END, Map.of("suspended", true));
                return Flux.fromIterable(events);
            }
        }
        sink.emit(AgentEventType.TEXT_DELTA, Map.of("delta", "已完成"));
        sink.emit(AgentEventType.TEXT, Map.of("text", "已完成"));
        sink.emit(AgentEventType.TURN_END, Map.of());
        return Flux.fromIterable(events);
    }

    private ToolInvocationResult invoke(NoopSession session, AgentTurn turn, ToolSpec tool, Map<String, Object> args) {
        ToolInvocationRequest request = new ToolInvocationRequest(
                tool.name(),
                args,
                session.request.userId(),
                session.sessionId(),
                turn.turnId(),
                turn.traceId(),
                session.request.requestId(),
                session.pendingConfirmId,
                session.request.deadlineEpochMs(),
                Map.of());
        try {
            return first(session.request.toolInvoker().invoke(request));
        } catch (RuntimeException e) {
            // 错误归一化：调用方抛出的异常同样不得向上泄露（TCK-12）。
            return ToolInvocationResult.error(AgentErrorCode.TOOL_FAILED, "工具暂不可用，请稍后重试");
        }
    }

    private static ToolInvocationResult first(org.reactivestreams.Publisher<ToolInvocationResult> publisher) {
        try {
            ToolInvocationResult result = Flux.from(publisher).blockFirst();
            return result == null
                    ? ToolInvocationResult.error(AgentErrorCode.INTERNAL, "工具无返回")
                    : result;
        } catch (RuntimeException e) {
            // 错误归一化：把底层异常映射为统一错误码与稳定措辞，绝不把内部细节抛给上层（TCK-12）。
            return ToolInvocationResult.error(AgentErrorCode.TOOL_FAILED, "工具暂不可用，请稍后重试");
        }
    }

    @Override
    public void confirm(AgentSession session, ConfirmDecision decision) {
        NoopSession noop = (NoopSession) session;
        if (decision.approved() && noop.pendingTool != null) {
            noop.approved = true;
        } else {
            noop.approved = false;
        }
    }

    /** 确认后继续执行（TCK-2 / TCK-3）：只执行一次，且重放不会二次执行。 */
    public Flux<AgentEvent> resumeAfterConfirm(AgentSession session, AgentTurn turn) {
        NoopSession noop = (NoopSession) session;
        if (noop.pendingTool == null) {
            return Flux.empty();
        }
        List<AgentEvent> events = new ArrayList<>();
        NoopEventSink sink = new NoopEventSink(session, turn, events);
        return continueAfterConfirm(noop, turn, sink, events);
    }

    private Flux<AgentEvent> continueAfterConfirm(
            NoopSession noop, AgentTurn turn, NoopEventSink sink, List<AgentEvent> events) {
        sink.emit(AgentEventType.CONFIRM_RESULT, Map.of("confirmId", noop.pendingConfirmId, "approved", noop.approved));
        if (noop.approved && !noop.executed) {
            ToolSpec tool = noop.request.tools().find(noop.pendingTool).orElse(null);
            if (tool != null) {
                ToolInvocationResult result = invoke(noop, turn, tool, noop.pendingArguments);
                noop.executed = true;
                sink.toolResult(tool.name(), result);
            }
        }
        sink.emit(AgentEventType.TEXT, Map.of("text", noop.approved ? "已执行" : "已取消"));
        sink.emit(AgentEventType.TURN_END, Map.of());
        noop.pendingTool = null;
        noop.pendingConfirmId = null;
        return Flux.fromIterable(events);
    }

    @Override
    public void cancel(AgentSession session, String reason) {
        ((NoopSession) session).canceled = true;
    }

    @Override
    public Snapshot snapshot(AgentSession session) {
        NoopSession noop = (NoopSession) session;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turns", noop.turnCount);
        payload.put("pendingConfirmId", noop.pendingConfirmId);
        payload.put("pendingTool", noop.pendingTool);
        payload.put("approved", noop.approved);
        payload.put("executed", noop.executed);
        return new Snapshot(RUNTIME_ID, RUNTIME_VERSION, session.sessionId(), System.currentTimeMillis(), payload);
    }

    @Override
    public AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request) {
        if (snapshot != null && !RUNTIME_ID.equals(snapshot.runtimeId())) {
            throw new RuntimeMismatchException(sessionId, RUNTIME_ID, snapshot.runtimeId());
        }
        NoopSession session = new NoopSession(sessionId, RUNTIME_ID, RUNTIME_VERSION, request);
        if (snapshot != null) {
            session.turnCount = intOf(snapshot.payload().get("turns"));
            session.pendingConfirmId = (String) snapshot.payload().get("pendingConfirmId");
            session.pendingTool = (String) snapshot.payload().get("pendingTool");
            session.approved = Boolean.TRUE.equals(snapshot.payload().get("approved"));
            session.executed = Boolean.TRUE.equals(snapshot.payload().get("executed"));
        }
        return session;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public void close(AgentSession session) {
        // 假实现无需释放资源
    }

    static final class NoopSession implements AgentSession {
        private final String sessionId;
        private final String runtimeId;
        private final String runtimeVersion;
        private final AgentRunRequest request;
        private int turnCount;
        private boolean canceled;
        private String pendingConfirmId;
        private String pendingTool;
        private Map<String, Object> pendingArguments = Map.of();
        private boolean approved;
        private boolean executed;

        NoopSession(String sessionId, String runtimeId, String runtimeVersion, AgentRunRequest request) {
            this.sessionId = sessionId;
            this.runtimeId = runtimeId;
            this.runtimeVersion = runtimeVersion;
            this.request = request;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public String runtimeId() {
            return runtimeId;
        }

        @Override
        public String runtimeVersion() {
            return runtimeVersion;
        }
    }

    static final class NoopEventSink {
        private final AgentSession session;
        private final AgentTurn turn;
        private final List<AgentEvent> events;
        private long seq;

        NoopEventSink(AgentSession session, AgentTurn turn, List<AgentEvent> events) {
            this.session = session;
            this.turn = turn;
            this.events = events;
        }

        void emit(AgentEventType type, Map<String, Object> payload) {
            events.add(AgentEvent.builder(type)
                    .session(session.sessionId())
                    .turn(turn.turnId())
                    .trace(turn.traceId())
                    .seq(seq++)
                    .putAll(payload)
                    .build());
        }

        void toolResult(String toolName, ToolInvocationResult result) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", toolName);
            payload.put("status", result.status().name().toLowerCase(java.util.Locale.ROOT));
            payload.put("content", result.content());
            payload.put("data", result.data());
            payload.put("meta", result.meta());
            emit(AgentEventType.TOOL_RESULT, payload);
        }
    }
}
