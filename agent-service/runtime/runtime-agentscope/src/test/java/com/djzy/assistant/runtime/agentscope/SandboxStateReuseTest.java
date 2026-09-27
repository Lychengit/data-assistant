package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 「上一轮的沙箱状态还能不能续用」的护栏（2026-09-27 真机定位后补）。
 *
 * <p><b>为什么值得单独钉一条</b>：技能里有一类流程天生跨轮 ——「先生在容器里生成文件 → 用户确认 → 再上传」。
 * 确认会把一轮拆成两轮，而每一轮都是一次沙箱 acquire。如果每轮都起一台新容器，上一轮生成的文件就没了：
 * 2026-09-27 真机实测的表现正是如此 —— 模型第二轮找不到文件，只好把 base64 分几次打印出来再手工拼，
 * 拼出来的 xlsx 是坏的（224 字节，真文件 6379 字节），用户拿到的报表打不开。
 *
 * <p>框架本来的设计是「续用」：一轮结束时把沙箱状态写进用户级的隔离槽位，下一轮读回来
 * {@code resume} 同一台容器，工作区因此是 preserved（{@code AbstractBaseSandbox#start} 的 Branch A）。
 * 这条路在真机上一直断着，原因不在框架，而在状态库：沙箱槽位号是路径式的
 * （{@code sandbox/user/<agentId>/<userId>}），JDBC 状态库拒绝含斜杠的槽位号，于是状态**从来没写进去过**
 * （见 {@code PlatformAgentStateStore} 类注释里的「第二个洞」）。平台的兜底是「每轮无条件清掉状态」，
 * 于是永远走不到 resume —— 那个兜底是为了掩盖第一个 bug 才存在的。
 *
 * <p>所以这条用例钉的是修完之后的判定，是三件事不是两件：
 * <b>容器还在 → 本轮开头状态留着</b>（真被杀过、容器却还活着的那种少数场景，还能 resume）；
 * <b>容器真的没了 → 清掉</b>（留着一条指向已删除容器的状态，框架会去 {@code docker start}
 * 一台不存在的容器，整轮直接抛异常挂掉）；<b>并且无论上面哪一种，这一轮跑完都要把状态作废</b>
 * （DR-57）—— 框架是在本轮**结束**时才把状态写回的，而那时容器已经被它删掉了，这条状态留给
 * 下一轮只会让框架按旧的内容哈希跳过技能投影：新起的空容器里没有技能脚本。
 */
class SandboxStateReuseTest {

    private static final String AGENT_NAME = "doctor-data-assistant";

    private static final Path WORKSPACE = Path.of("target", "sandbox-state-reuse-workspace");

    /** 这些用例只看「状态留不留」，不跑模型：真被调用到就说明用例写歪了。 */
    private static final Model UNUSED_MODEL = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new UnsupportedOperationException("这些用例不跑模型：只看沙箱状态"));
        }

        @Override
        public String getModelName() {
            return "unused-model";
        }
    };

    @Test
    void 容器还在时_状态留着_下一轮才resume得回同一台容器() throws IOException {
        Fixture fixture = fixture("alice");

        // 上一轮留下的状态：容器 c-1 还在（进程被杀过，但容器只是 exited。）
        // 状态本身由框架自己的序列化器生成，跟生产路径一字不差。
        String seeded = fixture.seedState("sbx-1", "c-1");
        fixture.probeReturns("exited");

        fixture.runtime.prepareSandbox(fixture.agent, fixture.ctx);

        assertEquals(List.of("c-1"), fixture.probed, "探的必须是状态里记着的那台容器");
        assertEquals(
                seeded,
                fixture.stateStore.load(fixture.key).orElse(""),
                "容器还在就不能清状态：清了这一轮就是一台全新的空容器，上一轮生成的文件（报表）就没了");
    }

    @Test
    void 容器没了时_清掉状态_否则框架会去start一台不存在的容器() throws IOException {
        Fixture fixture = fixture("alice");

        fixture.seedState("sbx-1", "c-1");
        fixture.probeReturns(null); // docker inspect 查不到这台容器

        fixture.runtime.prepareSandbox(fixture.agent, fixture.ctx);

        assertTrue(
                fixture.stateStore.load(fixture.key).isEmpty(),
                "容器没了必须清掉状态：留着的话框架会 resume 一台不存在的容器，SandboxLifecycleMiddleware 会把失败往上抛，整轮挂掉");
    }

    @Test
    void 没有上一轮的状态时_什么都不用做() throws IOException {
        Fixture fixture = fixture("alice");

        fixture.runtime.prepareSandbox(fixture.agent, fixture.ctx);

        assertTrue(fixture.probed.isEmpty(), "没有状态就没有容器可探：不该去叫 docker");
        assertTrue(fixture.stateStore.load(fixture.key).isEmpty());
    }

    @Test
    void 一轮跑完就把状态作废_下一轮才不会被它拖去复用一台已经删掉的容器() throws IOException {
        Fixture fixture = fixture("alice");
        fixture.seedState("sbx-1", "c-1");
        fixture.probeReturns("running"); // 容器还在：这一轮开头不该清状态

        // 前提：本轮开头只探不改（容器活着，状态留着）。
        fixture.runtime.prepareSandbox(fixture.agent, fixture.ctx);
        assertFalse(
                fixture.stateStore.load(fixture.key).isEmpty(),
                "前提：容器还在的时候，本轮开头不清状态");

        // 本轮跑完（不跑模型：假模型一被调到就报错，正好当"这一轮结束"）。
        fixture.runtime
                .stream(fixture.session, AgentTurn.of("t1", "tr1", "你好"))
                .onErrorResume(e -> Flux.empty())
                .blockLast();

        assertTrue(
                fixture.stateStore.load(fixture.key).isEmpty(),
                "一轮跑完必须把状态作废：框架在本轮结束时已经把容器删了，留下的那条状态会让下一轮"
                        + "按旧哈希跳过技能投影——新容器里没有技能脚本，模型照技能文档给的路径跑脚本只会"
                        + "得到 No such file or directory（真容器用例 SandboxDockerEndToEndTest 的第二轮钉过框架这个行为）");
    }

    @Test
    void 同一个用户换会话仍然共用一个槽位_沙箱是一个用户一台() throws IOException {
        Fixture fixture = fixture("alice");
        Fixture other = fixture("alice", "s2");

        assertEquals(fixture.key, other.key, "槽位按用户算：换了会话也还是同一台容器（L-21 钉过这条）");
    }

    /** 一套最小装配 + 那个用户这一轮的上下文。 */
    private static Fixture fixture(String userId) throws IOException {
        return fixture(userId, "s1");
    }

    private static Fixture fixture(String userId, String sessionId) throws IOException {
        recreate(WORKSPACE.resolve("sandbox-skills"));
        InMemoryAgentStateStore states = new InMemoryAgentStateStore();
        AgentscopeRuntimeAdapter runtime = new AgentscopeRuntimeAdapter(
                request -> UNUSED_MODEL,
                states,
                WORKSPACE,
                new InMemoryStore(),
                SkillProvisioner.NONE,
                enabledSandbox());
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        AgentSession session = runtime.start(request(userId, sessionId));
        HarnessAgent agent = ((AgentscopeRuntimeAdapter.AgentscopeSession) session).agent;
        return new Fixture(runtime, agent, session, ctx, new SessionSandboxStateStore(states, AGENT_NAME));
    }

    private static SandboxSettings enabledSandbox() {
        return new SandboxSettings(
                true,
                SandboxSettings.DEFAULT_IMAGE,
                SandboxSettings.DEFAULT_WORKSPACE_ROOT,
                null,
                null,
                null,
                SandboxSettings.DEFAULT_SHARED_PREFIXES,
                null);
    }

    private static AgentRunRequest request(String userId, String sessionId) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .requestId("req-" + sessionId)
                .tools(ToolCatalog.of(List.of(ToolSpec.read(
                        "iface_ping", "测试工具", Map.of("type", "object"),
                        com.djzy.assistant.spi.tool.ToolCategory.IFACE))))
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .systemPromptPrefix("提示词")
                .build();
    }

    private static void recreate(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.createDirectories(dir);
    }

    /** 一条用例要用的全部东西：装配、agent、上下文、状态库，加上探针的记账。 */
    private static final class Fixture {

        final AgentscopeRuntimeAdapter runtime;
        final HarnessAgent agent;
        final AgentSession session;
        final RuntimeContext ctx;
        final SessionSandboxStateStore stateStore;
        final SandboxIsolationKey key;
        final List<String> probed = new ArrayList<>();

        Fixture(
                AgentscopeRuntimeAdapter runtime,
                HarnessAgent agent,
                AgentSession session,
                RuntimeContext ctx,
                SessionSandboxStateStore stateStore) {
            this.runtime = runtime;
            this.agent = agent;
            this.session = session;
            this.ctx = ctx;
            this.stateStore = stateStore;
            this.key = SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_NAME).orElseThrow();
        }

        /**
         * 造一条「上一轮留下的状态」并返回它落盘的那个字符串。
         *
         * <p>刻意用框架自己的 {@link DockerSandboxClient#serializeState} 造，而不是手写一段 JSON：
         * 状态里带着框架的类型信息，手写的 JSON 反序列化不出来（第一版用例就是这么写歪的），
         * 那样测的就不是平台这段代码，而是测试自己编的格式对不对。
         */
        String seedState(String sessionId, String containerId) throws IOException {
            DockerSandboxState state = new DockerSandboxState();
            state.setSessionId(sessionId);
            state.setContainerId(containerId);
            String json = new DockerSandboxClient().serializeState(state);
            stateStore.save(key, json);
            return json;
        }

        /** 让探针回答「容器是这个状态」；{@code null} = 查不到（容器已经没了）。 */
        void probeReturns(String status) {
            runtime.setContainerStatusProbeForTest(containerId -> {
                probed.add(containerId);
                return status;
            });
        }
    }
}
