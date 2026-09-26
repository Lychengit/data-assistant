package com.djzy.assistant.tck;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.Snapshot;
import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

/**
 * agent 运行时契约回归（§18.11.7）：**同一套用例跑默认实现与 runtime-noop**，跑不过不许上线（§16-9）。
 *
 * <p>本类覆盖 TCK-1~5、TCK-8~10、TCK-12。TCK-6 / TCK-7（POST 段结果替换、大结果 spill）属于
 * 平台管线的行为，断言在 {@code ToolPipelineRunnerTest}；TCK-11（成本与 token 上报）对不调模型的
 * 实现不适用，由真实模型实现自行补齐。
 */
public final class RuntimeTck {

    private static final String USER = "u-1";
    private static final String SESSION = "s-1";

    private RuntimeTck() {}

    public static TckReport run(AgentRuntimePort runtime) {
        TckReport report = new TckReport(runtime.id());
        check1DeniedToolNotInvoked(runtime, report);
        check2AskThenConfirmExecutesOnce(runtime, report);
        check3UserRejectNoRetry(runtime, report);
        check4CancelStopsEverything(runtime, report);
        check5MaxItersNormalized(runtime, report);
        check8SnapshotResume(runtime, report);
        check9EventContract(runtime, report);
        check10ToolCoverage(runtime, report);
        check12ErrorsNormalized(runtime, report);
        report.skip("TCK-6/7 结果替换与大结果 spill", "由平台管线用例覆盖（ToolPipelineRunnerTest）");
        report.skip("TCK-11 成本与 token 上报", "不调模型的实现不适用");
        return report;
    }

    private static void check1DeniedToolNotInvoked(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> ToolInvocationResult.ok("ok", Map.of()));
        AgentRunRequest request = request(invoker, catalog("iface_test"), Map.of("deniedTools", List.of("iface_test")));
        AgentSession session = runtime.start(request);
        List<AgentEvent> events = collect(runtime.stream(session, turn("查询数据", "t1")));
        if (invoker.callCount() != 0) {
            report.fail("TCK-1 DENY 不产生 ToolInvoker 调用", "实际调用 " + invoker.callCount() + " 次");
            return;
        }
        if (events.stream().noneMatch(e -> e.type() == AgentEventType.ALL_TOOLS_DENIED)) {
            report.fail("TCK-1 DENY 不产生 ToolInvoker 调用", "未产出 ALL_TOOLS_DENIED 事件");
            return;
        }
        report.pass("TCK-1 DENY 不产生 ToolInvoker 调用");
    }

    private static void check2AskThenConfirmExecutesOnce(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> r.confirmId() == null
                ? ToolInvocationResult.awaitingConfirm("c-1", "确认执行？")
                : ToolInvocationResult.ok("执行完成", Map.of()));
        AgentRunRequest request = request(invoker, catalog("iface_write"));
        AgentSession session = runtime.start(request);
        List<AgentEvent> first = collect(runtime.stream(session, turn("查询数据", "t1")));
        if (first.stream().noneMatch(e -> e.type() == AgentEventType.TOOL_RESULT)) {
            report.fail("TCK-2 ASK 后确认只执行一次", "首次调用未产出 tool_result");
            return;
        }
        runtime.confirm(session, ConfirmDecision.approve("c-1", "同意"));
        List<AgentEvent> second = collect(runtime.stream(session, turn("", "t1")));
        long executed = second.stream()
                .filter(e -> e.type() == AgentEventType.TOOL_RESULT)
                .filter(e -> "ok".equals(String.valueOf(e.payload().get("status"))))
                .count();
        if (executed != 1) {
            report.fail("TCK-2 ASK 后确认只执行一次", "确认后 tool_result(ok) 数量 = " + executed);
            return;
        }
        // 重放同一轮不得二次执行
        collect(runtime.stream(session, turn("", "t1")));
        long okResults = invoker.requests().stream().filter(r -> r.confirmId() != null).count();
        if (okResults != 1) {
            report.fail("TCK-2 ASK 后确认只执行一次", "confirmId 非空的调用次数 = " + okResults);
            return;
        }
        report.pass("TCK-2 ASK 后确认只执行一次");
    }

    private static void check3UserRejectNoRetry(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> r.confirmId() == null
                ? ToolInvocationResult.awaitingConfirm("c-2", "确认执行？")
                : ToolInvocationResult.ok("执行完成", Map.of()));
        AgentRunRequest request = request(invoker, catalog("iface_write"));
        AgentSession session = runtime.start(request);
        collect(runtime.stream(session, turn("查询数据", "t1")));
        runtime.confirm(session, ConfirmDecision.reject("c-2", "不同意"));
        List<AgentEvent> after = collect(runtime.stream(session, turn("", "t1")));
        boolean executedAfterReject = after.stream()
                .anyMatch(e -> e.type() == AgentEventType.TOOL_RESULT
                        && "ok".equals(String.valueOf(e.payload().get("status"))));
        if (executedAfterReject) {
            report.fail("TCK-3 用户拒绝后不重试", "拒绝后仍然执行了工具");
            return;
        }
        report.pass("TCK-3 用户拒绝后不重试");
    }

    private static void check4CancelStopsEverything(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> ToolInvocationResult.ok("ok", Map.of()));
        AgentSession session = runtime.start(request(invoker, catalog("iface_test")));
        runtime.cancel(session, "用户取消");
        List<AgentEvent> events = collect(runtime.stream(session, turn("查询数据", "t1")));
        if (!events.isEmpty() || invoker.callCount() != 0) {
            report.fail("TCK-4 取消后不再产生任何调用与事件", "事件数=" + events.size() + "，工具调用数=" + invoker.callCount());
            return;
        }
        report.pass("TCK-4 取消后不再产生任何调用与事件");
    }

    private static void check5MaxItersNormalized(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> ToolInvocationResult.ok("ok", Map.of()));
        AgentSession session = runtime.start(request(invoker, catalog("iface_test")));
        List<AgentEvent> events = collect(runtime.stream(session, turn("死循环", "t1")));
        boolean terminated = events.stream().anyMatch(e -> e.type() == AgentEventType.EXCEED_MAX_ITERS);
        if (!terminated) {
            report.fail("TCK-5 超步数终止与错误归一化", "未产出 EXCEED_MAX_ITERS");
            return;
        }
        report.pass("TCK-5 超步数终止与错误归一化");
    }

    private static void check8SnapshotResume(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> ToolInvocationResult.ok("ok", Map.of()));
        AgentRunRequest request = request(invoker, catalog("iface_test"));
        AgentSession session = runtime.start(request);
        collect(runtime.stream(session, turn("你好", "t1")));
        Snapshot snapshot = runtime.snapshot(session);
        if (snapshot == null || !runtime.id().equals(snapshot.runtimeId())) {
            report.fail("TCK-8 快照导出与恢复", "快照 runtimeId 与实现不一致");
            return;
        }
        AgentSession resumed = runtime.resume(session.sessionId(), snapshot, request);
        Snapshot after = runtime.snapshot(resumed);
        if (!runtime.version().equals(after.runtimeVersion())) {
            report.fail("TCK-8 快照导出与恢复", "恢复后 runtimeVersion 不一致");
            return;
        }
        report.pass("TCK-8 快照导出与恢复");
    }

    private static void check9EventContract(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> ToolInvocationResult.ok("ok", Map.of()));
        AgentSession session = runtime.start(request(invoker, catalog("iface_test")));
        List<AgentEvent> events = collect(runtime.stream(session, turn("查询数据", "t1")));
        if (events.isEmpty()) {
            report.fail("TCK-9 事件顺序与必填字段", "没有任何事件");
            return;
        }
        for (AgentEvent event : events) {
            if (event.eventId() == null || event.turnId() == null || event.traceId() == null || event.type() == null) {
                report.fail("TCK-9 事件顺序与必填字段", "事件缺少必填字段：" + event);
                return;
            }
        }
        for (int i = 1; i < events.size(); i++) {
            if (events.get(i).seq() <= events.get(i - 1).seq()) {
                report.fail("TCK-9 事件顺序与必填字段", "seq 未严格递增");
                return;
            }
        }
        if (events.get(0).type() != AgentEventType.TURN_START
                || events.get(events.size() - 1).type() != AgentEventType.TURN_END) {
            report.fail("TCK-9 事件顺序与必填字段", "首尾事件不是 TURN_START / TURN_END");
            return;
        }
        report.pass("TCK-9 事件顺序与必填字段");
    }

    private static void check10ToolCoverage(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> ToolInvocationResult.ok("ok", Map.of()));
        AgentSession session = runtime.start(request(invoker, catalog("iface_test")));
        List<AgentEvent> events = collect(runtime.stream(session, turn("查询数据", "t1")));
        long toolCalls = events.stream().filter(e -> e.type() == AgentEventType.TOOL_CALL_START).count();
        if (toolCalls != invoker.callCount()) {
            report.fail("TCK-10 工具调用 100% 经 ToolInvoker", "TOOL_CALL_START=" + toolCalls + "，ToolInvoker=" + invoker.callCount());
            return;
        }
        report.pass("TCK-10 工具调用 100% 经 ToolInvoker");
    }

    private static void check12ErrorsNormalized(AgentRuntimePort runtime, TckReport report) {
        CountingToolInvoker invoker = new CountingToolInvoker(r -> {
            throw new IllegalStateException("内部错误：SQL 语句片段");
        });
        AgentSession session = runtime.start(request(invoker, catalog("iface_test")));
        List<AgentEvent> events;
        try {
            events = collect(runtime.stream(session, turn("查询数据", "t1")));
        } catch (RuntimeException e) {
            report.fail("TCK-12 错误归一化不泄露内部堆栈", "异常直接抛出到上层：" + e.getMessage());
            return;
        }
        for (AgentEvent event : events) {
            String payload = String.valueOf(event.payload());
            if (payload.contains("Exception") || payload.contains("\tat ") || payload.contains("SQL")) {
                report.fail("TCK-12 错误归一化不泄露内部堆栈", "事件载荷包含堆栈：" + payload);
                return;
            }
        }
        boolean normalized = events.stream().anyMatch(e -> e.type() == AgentEventType.ERROR
                || (e.type() == AgentEventType.TOOL_RESULT
                        && "error".equals(String.valueOf(e.payload().get("status")))));
        if (!normalized) {
            report.fail("TCK-12 错误归一化不泄露内部堆栈", "未产出归一化错误结果");
            return;
        }
        report.pass("TCK-12 错误归一化不泄露内部堆栈");
    }

    private static AgentRunRequest request(CountingToolInvoker invoker, ToolCatalog catalog) {
        return request(invoker, catalog, Map.of());
    }

    private static AgentRunRequest request(
            CountingToolInvoker invoker, ToolCatalog catalog, Map<String, Object> attributes) {
        AgentRunRequest.Builder builder = AgentRunRequest.builder()
                .userId(USER)
                .sessionId(SESSION)
                .requestId("r-1")
                .systemPromptPrefix("你是医生数据智能助理。")
                .tools(catalog)
                .toolInvoker(invoker)
                .deadlineEpochMs(System.currentTimeMillis() + 10_000)
                .maxIters(10);
        attributes.forEach(builder::attribute);
        return builder.build();
    }

    private static ToolCatalog catalog(String toolName) {
        return ToolCatalog.of(List.of(new ToolSpec(
                toolName, "测试工具", Map.of("type", "object"), SideEffect.READ, ToolCategory.IFACE, java.util.Set.of())));
    }

    private static AgentTurn turn(String text, String turnId) {
        return AgentTurn.of(turnId, "trace-1", text);
    }

    private static List<AgentEvent> collect(org.reactivestreams.Publisher<AgentEvent> publisher) {
        List<AgentEvent> events = new ArrayList<>();
        Flux.from(publisher).toIterable().forEach(events::add);
        return events;
    }


    static AtomicBoolean unused() {
        return new AtomicBoolean(false);
    }
}
