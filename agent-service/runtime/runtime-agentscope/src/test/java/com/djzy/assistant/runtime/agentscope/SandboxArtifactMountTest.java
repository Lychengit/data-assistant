package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentSession;
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
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 沙箱工件目录的护栏（2026-09-27）：容器里 {@code <工作区根>/out} 必须挂在宿主机的按用户目录上，
 * 而且「读工件」必须**不依赖容器活着**。
 *
 * <p>为什么这两条都值得写成用例：技能里「先生成文件 → 用户确认 → 再上传」天生跨轮，而框架每轮结束都会把容器
 * 删掉（{@code docker stop} + {@code docker rm --force}）。要么让产物不落在容器里（bind mount），
 * 要么在第二轮拿到一台空容器——2026-09-27 真机就是这个后果：模型第二轮找不到文件、把 base64 折成几段
 * 打印出来再手工拼，拼出的 xlsx 224 字节（真文件 6379），上传成功、链接有效、文件打不开。
 *
 * <p>所以第一条钉「挂载声明真的挂上了」（挂了但路径不对等于没挂），第二条钉「读字节只靠宿主目录」——
 * 用例里根本没有容器，也没有 Docker，却能把字节读出来，这才是确认之后那一轮能成立的原因。
 */
class SandboxArtifactMountTest {

    private static final Path WORKSPACE = Path.of("target", "sandbox-artifact-mount-workspace");

    private static final Model UNUSED_MODEL = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new UnsupportedOperationException("这些用例不跑模型：只看工件目录"));
        }

        @Override
        public String getModelName() {
            return "unused-model";
        }
    };

    @Test
    void 沙箱打开时_容器里的out目录挂在宿主机的按用户目录上() throws IOException {
        Fixture fixture = fixture("alice");

        SandboxContext context = fixture.ctx.get(SandboxContext.class);
        assertNotNull(context, "沙箱开着就必须有沙箱上下文（否则容器里连技能都没有）");

        WorkspaceSpec spec = context.getWorkspaceSpec();
        Object entry = spec.getEntries().get(SandboxArtifactMount.CONTAINER_DIR);
        assertInstanceOf(
                BindMountEntry.class,
                entry,
                "工作区清单里必须有一条 out/ 的挂载项，实际是：" + (entry == null ? "没有" : entry.getClass()));

        BindMountEntry mount = (BindMountEntry) entry;
        Path expected = WORKSPACE.resolve("sandbox-artifacts").resolve(SandboxSkillStaging.dirNameFor("alice"))
                .toAbsolutePath().normalize();
        assertEquals(
                expected.toString(),
                Path.of(mount.getHostPath()).toAbsolutePath().normalize().toString(),
                "挂载点必须是「这台机器上这个用户的」目录：挂到共用目录等于把甲的报表暴露给乙");
        assertTrue(Files.isDirectory(expected), "宿主挂载点必须真实存在，否则 docker 会把它当成要新建的匿名卷");
        assertTrue(
                spec.getEntries().keySet().stream().anyMatch(k -> k.contains("workspace_projection")),
                "加挂载不能把技能投影挤掉：投影项与挂载项要同时在（技能脚本得在容器里跑得起来）");
    }

    @Test
    void 读工件只靠宿主目录_没有容器也能把上一轮的报表读出来() throws IOException {
        Fixture fixture = fixture("alice");
        // 模拟「上一轮在容器里写 /workspace/out/perf.xlsx」的结果：字节落在宿主机这个用户的目录里。
        byte[] expected = "假装这是一份 xlsx（PK 开头的 zip）".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path hostFile = WORKSPACE.resolve("sandbox-artifacts").resolve(SandboxSkillStaging.dirNameFor("alice"))
                .resolve("perf.xlsx");
        Files.createDirectories(hostFile.getParent());
        Files.write(hostFile, expected);

        SandboxArtifactReader reader = fixture.ctx.get(CallAttributes.SANDBOX_ARTIFACT_READER);
        assertNotNull(reader, "沙箱开着就必须挂上「读沙箱产物」的口子，否则上传只能靠模型抄 base64");

        Optional<byte[]> read = reader.read("/workspace/out/perf.xlsx");

        assertTrue(read.isPresent(), "宿主目录里有这个文件就必须读得到——确认之后那一轮容器已经是新的一台了");
        assertArrayEquals(expected, read.orElseThrow());
    }

    @Test
    void 工件目录之外的路径一律拒绝_模型给的路径不能越界() {
        SandboxArtifactMount mount = new SandboxArtifactMount(WORKSPACE.resolve("sandbox-artifacts"));

        assertTrue(
                mount.hostPathFor("alice", "/workspace", "/workspace/out/../secrets.txt").isEmpty(),
                "带 .. 的路径必须被挡掉：这条路径最终是模型给的，不能让它读任意宿主文件");
        assertTrue(
                mount.hostPathFor("alice", "/workspace", "/workspace/skills/demo/SKILL.md").isEmpty(),
                "不在 out/ 下的路径不归这条挂载管，要留给容器那条路");
        assertTrue(mount.hostPathFor("alice", "/workspace", "").isEmpty(), "空路径不该翻出任何宿主文件");
    }

    @Test
    void 相对写法与绝对写法都认_技能说明书里给的是绝对路径() {
        SandboxArtifactMount mount = new SandboxArtifactMount(WORKSPACE.resolve("sandbox-artifacts"));
        Path userDir = WORKSPACE.resolve("sandbox-artifacts").resolve(SandboxSkillStaging.dirNameFor("alice"))
                .toAbsolutePath().normalize();

        assertEquals(
                userDir.resolve("perf.xlsx"),
                mount.hostPathFor("alice", "/workspace", "/workspace/out/perf.xlsx").orElseThrow());
        assertEquals(
                userDir.resolve("sub/perf.xlsx"),
                mount.hostPathFor("alice", "/workspace", "out/sub/perf.xlsx").orElseThrow());
    }

    private static Fixture fixture(String userId) throws IOException {
        recreate(WORKSPACE);
        AgentscopeRuntimeAdapter runtime = new AgentscopeRuntimeAdapter(
                request -> UNUSED_MODEL,
                new InMemoryAgentStateStore(),
                WORKSPACE,
                new InMemoryStore(),
                SkillProvisioner.NONE,
                enabledSandbox());
        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId("s1").build();
        AgentSession session = runtime.start(request(userId));
        HarnessAgent agent = ((AgentscopeRuntimeAdapter.AgentscopeSession) session).agent;
        runtime.prepareSandbox(agent, ctx);
        runtime.mountSandboxArtifactReader(agent, ctx);
        return new Fixture(ctx);
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

    private static AgentRunRequest request(String userId) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId("s1")
                .requestId("req-s1")
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

    private static final class Fixture {

        final RuntimeContext ctx;

        Fixture(RuntimeContext ctx) {
            this.ctx = ctx;
        }
    }
}
