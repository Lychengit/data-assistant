package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.tool.ToolCatalog;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 停止必须**真的**打断这一轮，而不是只把界面停住。
 *
 * <p>这条改造里最容易蒙混过去的一处：框架有两个 {@code interrupt} 重载——
 * 无参的那个（已标 {@code @Deprecated}）打的是「默认槽位」{@code (null, defaultSessionId)}，
 * 带身份的那个打的是 {@code (userId, sessionId)}。平台的每一轮都跑在**自己的槽位**上，
 * 所以喊错那个口子时：
 *
 * <ul>
 *   <li>界面看起来停了（平台自己把这一轮标记成结束、放掉坑位、记一条停止事件）；</li>
 *   <li>但模型循环根本没停——照样把答案生成完、照样烧 token，工具也照样会被调用。</li>
 * </ul>
 *
 * <p>这种「看起来成功了」的失败，靠读代码很难确认（两侧都是「调用了框架的停止」），
 * 所以这里用一个**慢速流式模型**把它钉死：模型每 100ms 吐一个词、一共 15 个词，
 * 中途喊停之后数一数后面还有几个词冒出来。
 */
class TurnInterruptTest {

    private static final Path WORKSPACE = Path.of("target", "turn-interrupt-workspace");

    private static AgentRunRequest request(String userId, String sessionId) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .requestId("req-" + sessionId)
                .tools(ToolCatalog.empty())
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 60_000)
                .maxIters(3)
                .systemPromptPrefix("p")
                .build();
    }

    private static AgentscopeRuntimeAdapter runtime(Model model) {
        return new AgentscopeRuntimeAdapter(request -> model, new InMemoryAgentStateStore(), WORKSPACE);
    }

    /** 一轮的观察结果：收了哪些事件、什么时候结束、模型被调用了几次。 */
    private static final class TurnProbe {
        final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        private final long start = System.currentTimeMillis();

        long elapsed() {
            return System.currentTimeMillis() - start;
        }

        long textDeltas() {
            return events.stream().filter(e -> e.type() == AgentEventType.TEXT_DELTA).count();
        }

        /** 第 n 个文本增量的到达时刻（毫秒）；没收到那么多就返回 -1。 */
        long nthTextDeltaAt(int n) {
            List<AgentEvent> deltas = events.stream()
                    .filter(e -> e.type() == AgentEventType.TEXT_DELTA)
                    .toList();
            return deltas.size() >= n ? elapsedOf(deltas.get(n - 1)) : -1;
        }

        private long elapsedOf(AgentEvent event) {
            return event.timestampEpochMs() - start;
        }

        boolean awaitFinish(long millis) throws InterruptedException {
            return finished.await(millis, TimeUnit.MILLISECONDS);
        }

        void run(AgentscopeRuntimeAdapter runtime, AgentSession session, String turnId) {
            AgentTurn turn = new AgentTurn(turnId, "trace-" + turnId, "问题", List.of(), Map.of());
            Flux.from(runtime.stream(session, turn)).subscribe(
                    events::add,
                    error -> finished.countDown(),
                    finished::countDown);
        }
    }

    @Test
    void 停止之后模型不再继续产出_带会话身份的_interrupt_才打得中() throws Exception {
        SlowModel model = new SlowModel(15);
        AgentscopeRuntimeAdapter runtime = runtime(model);
        AgentSession session = runtime.start(request("alice", "s-stop"));
        TurnProbe probe = new TurnProbe();
        probe.run(runtime, session, "t-1");

        // 等它确实开始产出了再喊停（否则测的是「还没开始就停」这种另一件事）。
        while (probe.textDeltas() < 2 && probe.elapsed() < 5_000) {
            Thread.sleep(20);
        }
        long cancelAt = probe.elapsed();
        runtime.cancel(session, "user_stop");
        assertTrue(probe.awaitFinish(5_000), "停止之后这一轮必须结束，不能一直挂着");

        long deltasAtCancel = probe.textDeltas();
        long endAt = probe.elapsed();
        assertTrue(
                deltasAtCancel < 15,
                "停止之后不该把整轮 15 个词都产出来（实际收到 " + deltasAtCancel + " 个）——说明 interrupt 没打到本会话的槽位上");
        assertTrue(
                endAt - cancelAt < 1_000,
                "停止必须很快生效：cancel 于 " + cancelAt + "ms、结束于 " + endAt + "ms");
        assertTrue(
                model.chunksProduced() < 15,
                "停止之后模型不该再把整轮拉完（SlowModel 一共 15 个词，实际被拉取了 " + model.chunksProduced() + " 个）");
    }

    @Test
    void 停一个会话不会把共用同一个_agent_的别的会话一起打断() throws Exception {
        SlowModel model = new SlowModel(15);
        AgentscopeRuntimeAdapter runtime = runtime(model);
        AgentSession alice = runtime.start(request("alice", "s-a"));
        AgentSession bob = runtime.start(request("bob", "s-b"));
        AgentSession aliceAgent = alice;
        // 前提：同一个工具面 → 共用同一个 agent（否则这条用例测不到「互相影响」）。
        assertEquals(
                ((AgentscopeRuntimeAdapter.AgentscopeSession) alice).agent.getAgentId(),
                ((AgentscopeRuntimeAdapter.AgentscopeSession) bob).agent.getAgentId(),
                "前提：两个人必须跑在同一个 agent 上");

        TurnProbe probeAlice = new TurnProbe();
        TurnProbe probeBob = new TurnProbe();
        probeAlice.run(runtime, aliceAgent, "t-a");
        probeBob.run(runtime, bob, "t-b");

        while (probeAlice.textDeltas() < 2 && probeAlice.elapsed() < 5_000) {
            Thread.sleep(20);
        }
        runtime.cancel(aliceAgent, "user_stop");
        assertTrue(probeAlice.awaitFinish(5_000), "被停的那一轮必须结束");

        assertTrue(
                probeAlice.textDeltas() < 15,
                "被停的会话不该跑完（实际 " + probeAlice.textDeltas() + " 个词）");
        assertTrue(probeBob.awaitFinish(10_000), "没被停的那一轮必须照常跑完");
        assertEquals(15, probeBob.textDeltas(), "别人的会话不该被牵连：他必须收到完整的 15 个词");
    }

    /**
     * 慢速流式模型：每 100ms 吐一个词。
     *
     * <p>{@code chunksProduced} 记的是「被拉取过多少个词」——停止之后这个数必须停在原地，
     * 因为框架打断的是这一轮，不是这一条 Rx 流本身（模型流被丢弃后不会再被订阅拉取）。
     */
    private static final class SlowModel implements Model {

        private final int totalChunks;
        private final AtomicInteger pulled = new AtomicInteger();

        SlowModel(int totalChunks) {
            this.totalChunks = totalChunks;
        }

        int chunksProduced() {
            return pulled.get();
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.range(1, totalChunks)
                    .delayElements(Duration.ofMillis(100))
                    .doOnNext(i -> pulled.incrementAndGet())
                    .map(i -> ChatResponse.builder()
                            .id("resp-" + i)
                            .content(List.of(TextBlock.builder().text("词" + i + " ").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "slow";
        }
    }
}
