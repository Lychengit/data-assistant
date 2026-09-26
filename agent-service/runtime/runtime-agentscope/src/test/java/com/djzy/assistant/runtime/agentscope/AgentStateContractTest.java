package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.Snapshot;
import com.djzy.assistant.spi.tool.ToolCatalog;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * **框架与平台之间那条最脆的缝，用一条用例钉住**：会话正文到底存在哪、长什么样。
 *
 * <p>平台的历史会话现在完全靠框架的会话状态投影出来（见 {@code SessionCatalog}），
 * 而那个状态是框架**自己**写进去的：键名 {@code "agent_state"} 在框架源码里是写死的字面量，
 * 没有对外常量；文档结构（{@code context} 里放的是 {@code Msg}）也是框架的定义。
 *
 * <p>所以升级 AgentScope 时最危险的失败不是编译不过，而是**悄悄改掉键名或文档结构**：
 * 编译照样过、心跳照样绿，只是用户的历史突然全空、换实例接着聊接不上。
 * 这条用例跑一次**真实的** HarnessAgent 调用（模型被替换成固定回包、不出网），
 * 然后从状态库里把会话读回来——框架改了哪一样，它都会红。
 */
class AgentStateContractTest {

    /** 平台读历史时用的键；必须与框架自己写的那个键一致。 */
    private static final String AGENT_STATE_KEY = "agent_state";

    /** 固定回包：只说一句话，不调工具。 */
    private static final Model CANNED_MODEL = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("resp-1")
                    .content(List.of(TextBlock.builder().text("上个月 1200 人次。").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "canned";
        }
    };

    @Test
    void 跑完一轮后_会话正文按框架约定的键存在状态库里() {
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        AgentscopeRuntimeAdapter runtime = new AgentscopeRuntimeAdapter(
                request -> CANNED_MODEL, stateStore, Path.of("target", "contract-workspace"));

        AgentRunRequest request = AgentRunRequest.builder()
                .userId("alice")
                .sessionId("s-contract")
                .requestId("t-1")
                .tools(ToolCatalog.empty())
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .systemPromptPrefix("你是医生数据智能助理。")
                .build();
        AgentSession session = runtime.start(request);
        List<AgentEvent> events = new ArrayList<>();
        Flux.from(runtime.stream(session, new AgentTurn("t-1", "trace-1", "心内科门诊量", List.of(), Map.of())))
                .doOnNext(events::add)
                .blockLast();

        assertTrue(
                events.stream().anyMatch(event -> event.type() == AgentEventType.TURN_END),
                "模型有回包就该正常收尾，实际事件：" + events);

        Optional<AgentState> stored = stateStore.get("alice", "s-contract", AGENT_STATE_KEY, AgentState.class);
        assertTrue(
                stored.isPresent(),
                "框架没往 '" + AGENT_STATE_KEY + "' 这个键里写会话状态——平台的历史会话会整段空掉，"
                        + "先看框架是不是改了存储键名或改了 AgentState 的文档结构");

        AgentState state = stored.orElseThrow();
        assertTrue(
                state.getContext().stream().anyMatch(AgentStateContractTest::isUserQuestion),
                "会话状态里没有用户提问，历史会话会缺问题一栏；上下文=" + state.getContext());
        assertTrue(
                state.getContext().stream().anyMatch(message -> message.getRole() == MsgRole.ASSISTANT),
                "会话状态里没有助手回答，历史会话只剩提问");
    }

    /** 用户提问 = 角色是 USER，且正文就是用户原话（第一块文本，不带平台附的快照）。 */
    private static boolean isUserQuestion(Msg message) {
        if (message.getRole() != MsgRole.USER) {
            return false;
        }
        List<io.agentscope.core.message.ContentBlock> blocks = message.getContent();
        if (blocks.isEmpty() || !(blocks.get(0) instanceof TextBlock first)) {
            return false;
        }
        return "心内科门诊量".equals(first.getText());
    }

    /** 快照（恢复）这条路也顺带钉一下：状态里存了东西，重建 agent 才能接着上下文聊。 */
    @Test
    void 快照导出运行时标识与版本() {
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        AgentscopeRuntimeAdapter runtime = new AgentscopeRuntimeAdapter(
                request -> CANNED_MODEL, stateStore, Path.of("target", "contract-workspace"));
        AgentRunRequest request = AgentRunRequest.builder()
                .userId("alice")
                .sessionId("s-contract-2")
                .requestId("t-2")
                .tools(ToolCatalog.empty())
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .build();

        Snapshot snapshot = runtime.snapshot(runtime.start(request));

        assertNotNull(snapshot);
        assertEquals(runtime.id(), snapshot.runtimeId());
        assertEquals(runtime.version(), snapshot.runtimeVersion());
    }
}
