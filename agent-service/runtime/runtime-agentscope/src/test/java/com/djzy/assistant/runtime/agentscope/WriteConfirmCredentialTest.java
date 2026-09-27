package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvoker;
import com.djzy.assistant.spi.tool.ToolSpec;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 写操作确认凭据的护栏（§19.9 / §18.4.5 W1）：**用户点了确认之后，平台必须真把凭据带给网关**。
 *
 * <p>这条链断过一次，症状是「对话里点了确认，写接口仍然 403」，审计里写着
 * {@code reason=CONFIRM_REQUIRED}（2026-09-27 实测）。原因不是权限配错，而是
 * 「用户点了确认」这件事只被框架那套 HITL 记住了——它给的 id 不是网关认的那张凭据。
 * 所以这里守三件事：
 * <ol>
 *   <li><b>批准才登记</b>：拒绝（或压根没有待确认项）时不许落凭据，否则等于给写操作开后门；</li>
 *   <li><b>登记了就要真的传下去</b>：不能只存在会话对象里，续跑那一轮的工具调用必须能拿到它
 *       （这是「点了确认还是 403」的直接原因）；</li>
 *   <li><b>一次性</b>：只给紧随其后的那一轮，取一次就清——留着会让后面几轮拿着废凭据去撞 403。</li>
 * </ol>
 */
class WriteConfirmCredentialTest {

    /** 只回一句「先调工具、再作答」的模型：这样才走得到平台工具回调。 */
    private static final class ToolThenTextModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            boolean hasToolResult = messages.stream().anyMatch(message -> message.getRole() == MsgRole.TOOL);
            List<ContentBlock> content = hasToolResult
                    ? List.of(TextBlock.builder().text("好的。").build())
                    : List.of(new ToolUseBlock("call-1", "iface_ping", Map.of("q", "1")));
            return Flux.just(ChatResponse.builder()
                    .id("resp")
                    .content(content)
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "tool-then-text";
        }
    }

    /** 记下每次登记（也用来控制返回什么 / 抛不抛）。 */
    private static final class RecordingRegistrar implements ConfirmRegistrar {

        final List<String[]> calls = Collections.synchronizedList(new ArrayList<>());
        String next = "cid-1";
        RuntimeException failure;

        @Override
        public String register(String userId, String sessionId, String turnId, String summary) {
            calls.add(new String[] {userId, sessionId, turnId, summary});
            if (failure != null) {
                throw failure;
            }
            return next;
        }
    }

    private static AgentRunRequest request(ToolInvoker invoker) {
        return AgentRunRequest.builder()
                .userId("u-1")
                .sessionId("s-1")
                .requestId("r-1")
                .tools(ToolCatalog.of(
                        List.of(ToolSpec.read("iface_ping", "测试工具", Map.of("type", "object"), ToolCategory.IFACE))))
                .toolInvoker(invoker == null ? invocation -> null : invoker)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .build();
    }

    /** 写工具的请求：副作用声明成 WRITE，框架才会在执行前拦下来问用户。 */
    private static AgentRunRequest writeRequest(ToolInvoker invoker) {
        return AgentRunRequest.builder()
                .userId("u-1")
                .sessionId("s-1")
                .requestId("r-1")
                .tools(ToolCatalog.of(List.of(new ToolSpec(
                        "iface_ping",
                        "测试写工具",
                        Map.of("type", "object"),
                        SideEffect.WRITE,
                        ToolCategory.IFACE,
                        Set.of()))))
                .toolInvoker(invoker == null ? invocation -> null : invoker)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .build();
    }
    private static AgentscopeRuntimeAdapter adapter(Path workspace, ConfirmRegistrar registrar) {
        Model model = new ToolThenTextModel();
        return new AgentscopeRuntimeAdapter(
                request -> model, new InMemoryAgentStateStore(), workspace, null, null, null, registrar);
    }

    private static void runTurn(AgentscopeRuntimeAdapter runtime, AgentSession session) {
        Flux.from(runtime.stream(session, new AgentTurn("t-1", "trace-1", "问题", List.of(), Map.of())))
                .blockLast();
    }

    @Test
    void 批准后登记的凭据会随续跑那一轮进入工具调用(@TempDir Path workspace) {
        RecordingRegistrar registrar = new RecordingRegistrar();
        registrar.next = "cid-7";
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AgentscopeRuntimeAdapter runtime = adapter(workspace, registrar);
        AgentSession session = runtime.start(request(invocation -> {
            seen.add(invocation.confirmId());
            return Flux.just(ToolInvocationResult.ok("结果", Map.of()));
        }));

        // 模拟框架那套 HITL：拦截到写调用、用户点了确认
        runtime.registerPendingToolCalls(
                session, List.of(new ToolUseBlock("call-1", "iface_ping", Map.of("q", "1"))));
        runtime.confirm(session, new ConfirmDecision("fw-1", true, "", System.currentTimeMillis()));

        assertEquals(1, registrar.calls.size(), "批准一次只登记一张凭据");
        assertEquals("u-1", registrar.calls.get(0)[0]);
        assertEquals("s-1", registrar.calls.get(0)[1]);
        assertTrue(
                registrar.calls.get(0)[3].contains("iface_ping"),
                () -> "摘要要能看出确认的是哪个写调用，实际：" + registrar.calls.get(0)[3]);

        runTurn(runtime, session);

        assertEquals(
                List.of("cid-7"),
                seen,
                "续跑那一轮的工具调用必须带上刚登记的凭据——不带就是「点了确认还是 403」");
        assertNull(
                ((AgentscopeRuntimeAdapter.AgentscopeSession) session).takeConfirmIdForTurn(),
                "凭据是一次性的：这一轮取走之后不许再留在会话上");    }

    @Test
    void 登记失败的凭据_这一轮取不到(@TempDir Path workspace) {
        RecordingRegistrar registrar = new RecordingRegistrar();
        registrar.failure = new IllegalStateException("库挂了");
        AgentscopeRuntimeAdapter runtime = adapter(workspace, registrar);
        AgentSession session = runtime.start(request(null));
        runtime.registerPendingToolCalls(
                session, List.of(new ToolUseBlock("call-1", "iface_ping", Map.of("q", "1"))));

        runtime.confirm(session, new ConfirmDecision("fw-1", true, "", System.currentTimeMillis()));

        assertNull(
                ((AgentscopeRuntimeAdapter.AgentscopeSession) session).takeConfirmIdForTurn(),
                "登记不上就是没有凭据：宁可这一轮被网关拦下，也不能伪造一个「已确认」（§19.9 不可绕过）");
    }

    @Test
    void 用户拒绝时不登记凭据(@TempDir Path workspace) {
        RecordingRegistrar registrar = new RecordingRegistrar();
        AgentscopeRuntimeAdapter runtime = adapter(workspace, registrar);
        AgentSession session = runtime.start(request(null));
        runtime.registerPendingToolCalls(
                session, List.of(new ToolUseBlock("call-1", "iface_ping", Map.of("q", "1"))));

        runtime.confirm(session, new ConfirmDecision("fw-1", false, "", System.currentTimeMillis()));

        assertEquals(0, registrar.calls.size(), "用户拒绝还敢登记凭据 = 给写操作开后门");
        assertNull(((AgentscopeRuntimeAdapter.AgentscopeSession) session).takeConfirmIdForTurn());
    }

    /**
     * 闭环：写工具被框架拦下 → 平台上出现确认事件并记下这次调用 → 用户点确认 →
     * **续跑那一轮的工具调用带着凭据**。这四步缺任何一步，用户看到的就是「点了确认还是没权限」。
     */
    @Test
    void 写工具从拦下到批准续跑_凭据跟着续跑那一轮走(@TempDir Path workspace) {
        RecordingRegistrar registrar = new RecordingRegistrar();
        registrar.next = "cid-9";
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AgentscopeRuntimeAdapter runtime = adapter(workspace, registrar);
        AgentSession session = runtime.start(writeRequest(invocation -> {
            seen.add(invocation.confirmId());
            return Flux.just(ToolInvocationResult.ok("已上传", Map.of()));
        }));

        // 第一轮：框架应当拦下写调用、发确认事件，且**不执行**工具
        List<com.djzy.assistant.spi.AgentEvent> first = Flux.from(runtime.stream(
                        session, new AgentTurn("t-1", "trace-1", "导出上月绩效", List.of(), Map.of())))
                .collectList()
                .block();
        long confirms = first.stream().filter(e -> e.type() == AgentEventType.AWAITING_CONFIRM).count();
        assertEquals(1, confirms, "写调用必须产生且只产生一次确认请求");
        assertTrue(seen.isEmpty(), "用户还没确认，工具就不该被执行");

        assertEquals(0, registrar.calls.size(), "用户还没点确认，平台就不该登记凭据");

        // 用户点确认
        runtime.confirm(session, new ConfirmDecision("fw-1", true, "", System.currentTimeMillis()));
        assertEquals(1, registrar.calls.size(), "被拦下的调用要记在会话上，否则用户点确认时平台不知道该确认哪一次调用");

        // 续跑：这一次工具真的执行，并且带着刚登记的凭据
        List<com.djzy.assistant.spi.AgentEvent> second = Flux.from(runtime.stream(
                        session, new AgentTurn("t-2", "trace-1", "", List.of(), Map.of())))
                .collectList()
                .block();

        assertEquals(List.of("cid-9"), seen, "续跑那一轮的工具调用必须带上刚登记的凭据");

        // 确认结果事件必须带着**那张卡的编号与结论**：框架只说「有人答复了」，
        // 编号不补的话事件长成 {"confirmId":"","approved":false}，前端对不回卡片，
        // 只能再弹一张新卡——用户就被要求确认第二次（2026-09-27 实测）。
        String cardId = first.stream()
                .filter(e -> e.type() == AgentEventType.AWAITING_CONFIRM)
                .findFirst()
                .map(e -> String.valueOf(e.payload().get("confirmId")))
                .orElse("");
        assertTrue(cardId != null && !cardId.isBlank(), "确认卡必须带平台发的编号");

        com.djzy.assistant.spi.AgentEvent confirmResult = second.stream()
                .filter(e -> e.type() == AgentEventType.CONFIRM_RESULT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("续跑那一轮应当有确认结果事件，实际事件：" + second));
        assertEquals(cardId, String.valueOf(confirmResult.payload().get("confirmId")), "确认结果要能对回原来那张卡");
        assertEquals(Boolean.TRUE, confirmResult.payload().get("approved"), "用户批准了，结论要如实带出去");
    }
    @Test
    void 没有待确认调用时不登记凭据(@TempDir Path workspace) {
        RecordingRegistrar registrar = new RecordingRegistrar();
        AgentscopeRuntimeAdapter runtime = adapter(workspace, registrar);
        AgentSession session = runtime.start(request(null));

        runtime.confirm(session, new ConfirmDecision("fw-1", true, "", System.currentTimeMillis()));

        assertEquals(0, registrar.calls.size(), "没有待确认的东西还登记凭据 = 凭据会落到不相干的调用上");
    }
}