package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * T1-06 的护栏：**一个 agent 服务多个用户**，而且「每轮才变的东西」确实是按轮传的。
 *
 * <p>这条改造最容易犯的错是「看起来共享了，其实串了」：agent 共享之后，若把系统提示词、
 * 工具回调这类**属于某一次调用**的东西留在 agent 实例上（或留在工具对象里），
 * 表现是「A 用户的问题用 B 用户的提示词回答」「B 用户的接口用 A 用户的凭据调用」——
 * 单用户单会话测试完全发现不了，因为只有一个值。
 *
 * <p>所以这里不测「函数返回什么」，而是**真的跑两轮**（两个用户、同一个工具面），
 * 再回头检查：agent 是不是同一个、每一轮看到的提示词是不是各自的、工具回调拿到的是不是各自的用户。
 */
class SharedAgentTest {

    private static final Path WORKSPACE = Path.of("target", "shared-agent-workspace");

    private static ToolCatalog catalogWith(String toolName) {
        return ToolCatalog.of(List.of(ToolSpec.read(toolName, "测试工具", Map.of("type", "object"), ToolCategory.IFACE)));
    }

    private static AgentRunRequest request(
            String userId, String sessionId, ToolCatalog catalog, String prompt, com.djzy.assistant.spi.tool.ToolInvoker invoker) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .requestId("req-" + sessionId)
                .tools(catalog)
                .toolInvoker(invoker == null ? invocation -> null : invoker)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .systemPromptPrefix(prompt)
                .build();
    }

    private static io.agentscope.harness.agent.HarnessAgent agentOf(AgentSession session) {
        return ((AgentscopeRuntimeAdapter.AgentscopeSession) session).agent;
    }

    /** 跑完一轮（消费掉整个事件流，确保模型真的被调用过）。 */
    private static void runTurn(AgentscopeRuntimeAdapter runtime, AgentSession session, String turnId, String text) {
        Flux.from(runtime.stream(session, new AgentTurn(turnId, "trace-" + turnId, text, List.of(), Map.of())))
                .blockLast();
    }

    @Test
    void 同一个工具面_多个用户多个会话共用同一个_agent_实例() {
        AgentscopeRuntimeAdapter runtime =
                new AgentscopeRuntimeAdapter(request -> TEXT_MODEL, new InMemoryAgentStateStore(), WORKSPACE);
        ToolCatalog catalog = catalogWith("iface_ping");

        AgentSession alice = runtime.start(request("alice", "s-alice", catalog, "提示词-A", null));
        AgentSession bob = runtime.start(request("bob", "s-bob", catalog, "提示词-B", null));

        assertSame(agentOf(alice), agentOf(bob), "同一个工具面必须共用 agent：不共用就等于每轮重建一次装配（T1-06）");
    }

    @Test
    void 工具面不同_各用各的_agent_而不是把所有人的工具并在一起() {
        AgentscopeRuntimeAdapter runtime =
                new AgentscopeRuntimeAdapter(request -> TEXT_MODEL, new InMemoryAgentStateStore(), WORKSPACE);

        AgentSession alice = runtime.start(request("alice", "s-1", catalogWith("iface_ping"), "p", null));
        AgentSession bob = runtime.start(request("bob", "s-2", catalogWith("iface_other"), "p", null));

        assertNotSame(agentOf(alice), agentOf(bob), "工具面不同不能共用一个 agent，否则工具会互相可见（越权）");
        assertEquals(
                List.of("iface_ping"),
                agentOf(alice).getToolkit().getToolNames().stream().sorted().toList());
        assertEquals(
                List.of("iface_other"),
                agentOf(bob).getToolkit().getToolNames().stream().sorted().toList());
    }

    @Test
    void 系统提示词按轮注入_共享实例上两个用户各自拿到自己的提示词() {
        RecordingModel model = new RecordingModel(false);
        AgentscopeRuntimeAdapter runtime =
                new AgentscopeRuntimeAdapter(request -> model, new InMemoryAgentStateStore(), WORKSPACE);
        ToolCatalog catalog = catalogWith("iface_ping");

        AgentSession alice = runtime.start(request("alice", "s-a", catalog, "提示词-A", null));
        AgentSession bob = runtime.start(request("bob", "s-b", catalog, "提示词-B", null));
        runTurn(runtime, alice, "t-a", "第一个用户的问题");
        runTurn(runtime, bob, "t-b", "第二个用户的问题");

        assertSame(agentOf(alice), agentOf(bob), "前提：这两轮确实跑在同一个 agent 上");
        assertEquals("提示词-A", systemPromptOf(model.calls.get(0)), "第一轮的系统提示词必须来自第一个请求");
        assertEquals("提示词-B", systemPromptOf(model.calls.get(1)), "第二轮的系统提示词必须来自第二个请求");
    }

    @Test
    void 工具回调按轮传递_同一个_agent_上两个用户各自走自己的平台管线() {
        RecordingModel model = new RecordingModel(true);
        AgentscopeRuntimeAdapter runtime =
                new AgentscopeRuntimeAdapter(request -> model, new InMemoryAgentStateStore(), WORKSPACE);
        ToolCatalog catalog = catalogWith("iface_ping");
        List<String> invokedBy = Collections.synchronizedList(new ArrayList<>());

        AgentSession alice = runtime.start(request("alice", "s-a", catalog, "p", invocation -> {
            invokedBy.add(invocation.userId());
            return Flux.just(ToolInvocationResult.ok("结果", Map.of()));
        }));
        AgentSession bob = runtime.start(request("bob", "s-b", catalog, "p", invocation -> {
            invokedBy.add(invocation.userId());
            return Flux.just(ToolInvocationResult.ok("结果", Map.of()));
        }));
        runTurn(runtime, alice, "t-a", "问题 A");
        runTurn(runtime, bob, "t-b", "问题 B");

        assertSame(agentOf(alice), agentOf(bob), "前提：这两轮确实跑在同一个 agent 上");
        assertEquals(
                List.of("alice", "bob"),
                invokedBy,
                "工具对象里不许存发起者：每一轮的工具调用必须回到**这一轮**的平台管线（否则就是用别人的身份查数据）");
    }

    /** 只回一句话的模型：用来跑「不调工具」的轮次。 */
    private static final Model TEXT_MODEL = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(reply(List.of(TextBlock.builder().text("好的。").build())));
        }

        @Override
        public String getModelName() {
            return "text-only";
        }
    };

    private static ChatResponse reply(List<ContentBlock> content) {
        return ChatResponse.builder().id("resp").content(content).finishReason("stop").build();
    }

    private static String systemPromptOf(List<Msg> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.SYSTEM)
                .map(Msg::getTextContent)
                .findFirst()
                .orElse("");
    }

    /**
     * 会记录每次模型调用收到什么的模型。
     *
     * <p>顺带模拟「先调工具、再作答」：{@code callToolFirst} 为真时，第一次推理回一个工具调用，
     * 看到工具结果之后再回一句话——这样才能验证工具回调那条链。
     */
    private static final class RecordingModel implements Model {

        private final boolean callToolFirst;
        final List<List<Msg>> calls = Collections.synchronizedList(new ArrayList<>());

        RecordingModel(boolean callToolFirst) {
            this.callToolFirst = callToolFirst;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            boolean hasToolResult = messages.stream().anyMatch(message -> message.getRole() == MsgRole.TOOL);
            if (callToolFirst && !hasToolResult) {
                return Flux.just(reply(List.of(new ToolUseBlock("call-1", "iface_ping", Map.of("q", "1")))));
            }
            return Flux.just(reply(List.of(TextBlock.builder().text("好的。").build())));
        }

        @Override
        public String getModelName() {
            return "recording";
        }
    }
}
