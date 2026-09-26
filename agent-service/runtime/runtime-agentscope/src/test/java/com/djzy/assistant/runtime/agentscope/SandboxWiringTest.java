package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.tool.ToolCategory;
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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * H-05 装配的守护用例（**不需要 Docker**）：开关关着时行为不能变，开着时"哪些走容器、哪些走共享库"必须对。
 *
 * <p>为什么这些断言值得写：沙箱这条路最容易犯的错不是崩溃，而是**静默地换了一套语义**——
 * 关着开关却多了个壳工具（模型能跑命令了）、开着开关却让整块工作区都进了容器（多副本各看各的）、
 * 或者路由前缀写错导致"技能明明下发了，模型却说没有"。三种都不会报错，只会让人莫名其妙。
 *
 * <p>真容器那一段（命令真的在容器里跑、共享前缀真的落在共享库）由 {@code SandboxDockerEndToEndTest} 兜，
 * 它在本机没有 Docker 时跳过。
 */
class SandboxWiringTest {

    private static final Path WORKSPACE = Path.of("target", "sandbox-wiring-workspace");

    /** 这些用例只看**装配**，不跑模型：真被调用到就说明用例写歪了，直接报错比默默通过好。 */
    private static final Model UNUSED_MODEL = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new UnsupportedOperationException("这些用例不跑模型：只看装配"));
        }

        @Override
        public String getModelName() {
            return "unused-model";
        }
    };

    /** 打开沙箱的最小配置：镜像用小而通用的那个，其余走默认。 */
    private static SandboxSettings enabledSandbox() {
        return new SandboxSettings(
                true, SandboxSettings.DEFAULT_IMAGE, SandboxSettings.DEFAULT_WORKSPACE_ROOT, null, null, null,
                SandboxSettings.DEFAULT_SHARED_PREFIXES, null);
    }

    private static AgentscopeRuntimeAdapter runtime(SandboxSettings sandbox, BaseStore store) {
        return new AgentscopeRuntimeAdapter(
                request -> UNUSED_MODEL,
                new InMemoryAgentStateStore(),
                WORKSPACE,
                store,
                SkillProvisioner.NONE,
                sandbox);
    }

    /** 一个"这一轮"的请求：字段取最小集，够装配用就行。 */
    private static AgentRunRequest request(String userId, String sessionId) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .requestId("req-" + sessionId)
                .tools(ToolCatalog.of(List.of(ToolSpec.read(
                        "iface_ping", "测试工具", Map.of("type", "object"), ToolCategory.IFACE))))
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 30_000)
                .maxIters(3)
                .systemPromptPrefix("提示词")
                .build();
    }

    /** 从会话里拿回这次装配出来的 agent（{@code start()} 只装配，不调模型）。 */
    private static HarnessAgent agentOf(AgentscopeRuntimeAdapter runtime, String userId, String sessionId) {
        AgentSession session = runtime.start(request(userId, sessionId));
        return ((AgentscopeRuntimeAdapter.AgentscopeSession) session).agent;
    }

    @Test
    void 配了跨实例执行锁时_装配对象上带着它_关着沙箱时不带() {
        SandboxExecutionGuard guard = key -> () -> {};

        SandboxSettings withLock = new SandboxSettings(
                true, SandboxSettings.DEFAULT_IMAGE, SandboxSettings.DEFAULT_WORKSPACE_ROOT, null, null, null,
                SandboxSettings.DEFAULT_SHARED_PREFIXES, guard);

        assertSame(guard, withLock.toFilesystemSpec().getExecutionGuard(),
                "agent 级那个装配对象必须带锁（框架就是从这里取锁的）");
        assertSame(guard, withLock.toProjectingFilesystemSpec().getExecutionGuard(),
                "每轮用的那个（带技能投影）也必须带锁：两处不一致就等于「有时锁、有时不锁」");

        SandboxSettings lockButDisabled = new SandboxSettings(
                false, SandboxSettings.DEFAULT_IMAGE, SandboxSettings.DEFAULT_WORKSPACE_ROOT, null, null, null,
                SandboxSettings.DEFAULT_SHARED_PREFIXES, guard);
        assertNull(lockButDisabled.toFilesystemSpec().getExecutionGuard(),
                "沙箱关着的时候不该留着锁：没有任何东西需要它保护，却会白占一条数据库连接");
        assertNull(SandboxSettings.disabled().toFilesystemSpec().getExecutionGuard(), "默认档（关着沙箱）本来就没有锁");
    }

    @Test
    void 默认不开沙箱_没有沙箱也没有壳工具() {
        HarnessAgent agent = agentOf(runtime(SandboxSettings.disabled(), null), "alice", "s1");

        AbstractFilesystem filesystem = agent.getWorkspaceManager().getFilesystem();
        // 注意这里断言的是「没有路由装配」，不是「文件系统不是沙箱类」：框架默认给的是本机 overlay
        // （LocalFilesystemWithShell，它恰好也实现了 AbstractSandboxFilesystem），真正的护栏是下面的工具面。
        assertFalse(
                filesystem instanceof RoutedSandboxFilesystem,
                "默认不该出现沙箱：没有执行面才是改造前的行为");
        assertFalse(
                agent.getToolkit().getToolNames().contains(ShellExecuteTool.NAME),
                "默认不该有壳工具（ADR-31 基线：工具执行一律回到平台）");
        assertTrue(
                agent.getToolkit().getToolNames().contains("iface_ping"),
                "前提：平台注册的工具还在（否则这条用例可能因为「工具面为空」而假通过）");
    }

    @Test
    void 打开沙箱_容器当主_共享前缀走共享库_其余留在容器里() {
        BaseStore store = new InMemoryStore();
        HarnessAgent agent = agentOf(runtime(enabledSandbox(), store), "alice", "s1");

        AbstractFilesystem filesystem = agent.getWorkspaceManager().getFilesystem();
        assertTrue(
                filesystem instanceof RoutedSandboxFilesystem,
                "打开沙箱后文件系统应当是「沙箱当主 + 前缀路由」，实际：" + filesystem.getClass().getName());
        RoutedSandboxFilesystem routed = (RoutedSandboxFilesystem) filesystem;

        assertNotSame(
                routed.primary(),
                routed.backendFor("skills/demo/SKILL.md"),
                "skills/ 必须走共享库：技能要跨实例看到同一份（H-06a）");
        assertSame(
                routed.primary(),
                routed.backendFor("scratch/tmp.txt"),
                "没被枚举的路径留在容器里：临时文件、脚本产出的中间结果本来就该跟着容器走");
    }

    @Test
    void 打开沙箱_才放行壳工具_而且只多它一个() {
        HarnessAgent agent = agentOf(runtime(enabledSandbox(), new InMemoryStore()), "alice", "s1");

        List<String> tools = agent.getToolkit().getToolNames().stream().sorted().toList();
        assertTrue(tools.contains(ShellExecuteTool.NAME), "沙箱里没有壳工具就等于白开，实际：" + tools);
        assertTrue(tools.contains("iface_ping"), "平台注册的工具当然还在");
        assertFalse(tools.contains("web_search"), "框架内建的出网工具必须照旧被移掉，实际：" + tools);
        assertFalse(tools.contains("web_fetch"), "框架内建的出网工具必须照旧被移掉，实际：" + tools);
    }

    @Test
    void 打开沙箱但没开共享库_共享前缀也落在容器里_如实承认这一点() {
        HarnessAgent agent = agentOf(runtime(enabledSandbox(), null), "alice", "s1");

        AbstractFilesystem filesystem = agent.getWorkspaceManager().getFilesystem();
        assertTrue(
                filesystem instanceof SandboxBackedFilesystem,
                "没有共享库时就是纯容器文件系统（连路由都没有），实际：" + filesystem.getClass().getName());
        assertFalse(
                filesystem instanceof RoutedSandboxFilesystem,
                "没有共享库就没有路由可接：技能只能留在容器里，多副本各看各的——这是配置问题（启动日志有告警）");
    }

    @Test
    void 沙箱设置的翻译_镜像与资源上限按配置生效_而且关掉了框架默认投影() {
        SandboxSettings settings =
                new SandboxSettings(true, "ubuntu:22.04", "/workspace", 512L * 1024 * 1024, 2L, "bridge", List.of("skills/"), null);

        SandboxContext context = settings.toFilesystemSpec().toSandboxContext(WORKSPACE);
        DockerSandboxClientOptions options = (DockerSandboxClientOptions) context.getClientOptions();

        assertEquals("ubuntu:22.04", options.getImage(), "镜像要按配置走");
        assertEquals("/workspace", context.getWorkspaceSpec().getRoot(), "工作区根目录要按配置走");
        assertEquals(512L * 1024 * 1024, options.getMemorySizeBytes(), "内存上限要真的传到容器上");
        assertEquals(2L, options.getCpuCount(), "CPU 上限要真的传到容器上");
        assertEquals("bridge", options.getNetwork(), "网络名要真的传到容器上");
        assertEquals(IsolationScope.USER, context.getIsolationScope(), "隔离粒度：一个用户一个容器/一个命名空间");
        assertNull(
                context.getWorkspaceSpec().getEntries().get("__workspace_projection__"),
                "框架默认的「把本机工作目录打 tar 塞进容器」必须关掉：多副本下每台机器目录内容不同，来源不确定");
    }

    @Test
    void 共享前缀在共享库里的键位_与远端模式逐字一致_切开关不会让技能全没了() {
        InMemoryStore store = new InMemoryStore();
        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();

        // 远端模式（H-04）：这是改造前就已经在用的那套，技能由它写进共享库。
        AbstractFilesystem remoteMode = new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .toFilesystem(WORKSPACE, "doctor-data-assistant", IsolationScope.USER.toNamespaceFactory());
        assertTrue(remoteMode.write(ctx, "skills/demo/SKILL.md", "技能正文").isSuccess(), "前提：远端模式写成功");

        // 沙箱模式：同一个前缀必须落到同一个键位上，否则切开关就等于"技能全没了"。
        RoutedSandboxFilesystem sandboxMode = new RoutedSandboxFilesystem(
                new FakeSandboxFilesystem(),
                SandboxSharedRoutes.build(
                        store, WORKSPACE, "doctor-data-assistant", IsolationScope.USER, List.of("skills/")));

        assertEquals(
                "技能正文",
                sandboxMode.read(ctx, "/skills/demo/SKILL.md", 0, 100).fileData().content(),
                "沙箱模式下读同一个技能，必须读到远端模式写进去的那一份");
        assertTrue(
                sandboxMode.write(ctx, "/skills/demo/notes.md", "新写的").isSuccess(),
                "沙箱模式下也要能写回共享库");
        assertEquals(
                "新写的",
                remoteMode.read(ctx, "skills/demo/notes.md", 0, 100).fileData().content(),
                "反向也要成立：沙箱模式写进去的，远端模式读得到");
    }
/**
     * 技能下发（H-06a）在**沙箱模式**下照旧落在共享库，而且一个字节都没进容器。
     *
     * <p>为什么值得单独钉一条：技能下发走的是 agent 的文件系统，沙箱一开，那个文件系统就变成了
     * {@link RoutedSandboxFilesystem}——路由会不会把技能写到容器里去、路径口径（无前导斜杠、{@code uploadFiles} 放脚本）
     * 还作不作数，都是"写错了也不报错、只是模型突然说没有技能"的那类问题（台账 L-15）。
     *
     * <p>这里把 {@code WorkspaceSkillProvisioner} 一轮的动作原样走一遍（它自己没有沙箱模式的分支，
     * 所以这条用例等价于"它换到沙箱模式下还对不对"）：写正文 → 传脚本 → 换版 edit → 撤销 delete。
     */
    @Test
    void 技能下发的动作在沙箱模式下照旧落在共享库_一个字节都不进容器() {
        InMemoryStore store = new InMemoryStore();
        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RoutedSandboxFilesystem filesystem = new RoutedSandboxFilesystem(
                sandbox,
                SandboxSharedRoutes.build(
                        store, WORKSPACE, "doctor-data-assistant", IsolationScope.USER, List.of("skills/")));

        // ① 写技能说明：注意是**无前导斜杠**的工作区相对路径（provisioner 就是这么写的）。
        assertTrue(
                filesystem.write(ctx, "skills/demo/SKILL.md", "第一版技能正文").isSuccess(),
                "技能说明要写得进共享库");
        // ② 传技能里的脚本：provisioner 刻意走 uploadFiles（write 只收字符串，脚本可能是二进制）。
        List<FileUploadResponse> uploaded = filesystem.uploadFiles(
                ctx,
                List.of(Map.entry(
                        "skills/demo/scripts/run.sh", "echo SKILL_SCRIPT".getBytes(StandardCharsets.UTF_8))));
        assertTrue(
                uploaded.get(0).isSuccess(),
                "技能里的脚本要能通过 uploadFiles 写进共享库，实际：" + uploaded.get(0).error());
        // ③ 换版：write 是「不覆盖」语义，所以 provisioner 的写法是「读得到就 edit 整段替换」（台账 L-03）。
        ReadResult current = filesystem.read(ctx, "skills/demo/SKILL.md", 0, 100);
        assertTrue(current.isSuccess(), "先读回来（provisioner 靠这次读决定 write 还是 edit）");
        assertTrue(
                filesystem.edit(ctx, "skills/demo/SKILL.md", current.fileData().content(), "第二版技能正文", false)
                        .isSuccess(),
                "换版要能 edit 成功");
        // ④ 撤销：授权被收走时，provisioner 会照索引把技能写下的文件删干净。
        assertTrue(filesystem.delete(ctx, "skills/demo/scripts/run.sh").isSuccess(), "撤销要能删掉脚本");

        // 关键：以上动作**一个都没落到容器里**。技能是「跨实例共享」的东西，进容器就等于多副本各看各的。
        assertTrue(
                sandbox.files.isEmpty(),
                "技能相关的读写不该出现在容器里，实际容器里有：" + sandbox.files.keySet());
        assertTrue(
                sandbox.executedCommands.isEmpty(),
                "技能下发不该在容器里跑命令，实际跑了：" + sandbox.executedCommands);

        // 换个"另一台实例"读：留下的是「新版正文」，撤掉的脚本已经没了。
        AbstractFilesystem anotherNode = new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .toFilesystem(WORKSPACE, "doctor-data-assistant", IsolationScope.USER.toNamespaceFactory());
        assertEquals(
                "第二版技能正文",
                anotherNode.read(ctx, "skills/demo/SKILL.md", 0, 100).fileData().content(),
                "换版之后的正文要是新那份");
        assertFalse(anotherNode.exists(ctx, "skills/demo/scripts/run.sh"), "撤销之后脚本在共享库里也该没了");
    }

    /**
     * 沙箱打开时的**每轮装配**（H-06b）：技能先抄到本机，再把「用这份当投影源」交给框架。
     *
     * <p>这一条补的是前面几条盖不到的地方：前面验的是「文件系统的路由」（技能写哪去、读哪来），
     * 这里验的是「容器里为什么会有技能的副本」——框架的提示词已经把技能目录写成了容器里的路径
     * （{@code /workspace/skills/<技能>}），容器里就必须真有这份文件，否则模型会照着一个不存在的路径去跑脚本。
     */
    @Test
    void 沙箱打开时_每轮把技能抄到本机_并交给框架投影进容器() throws IOException {
        InMemoryStore store = new InMemoryStore();
        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        // 技能是先前某一轮下发进共享库的（这里直接写，等价于 WorkspaceSkillProvisioner 干的那件事）。
        assertTrue(工作区(store).write(ctx, "skills/demo/SKILL.md", "技能正文").isSuccess(), "前提：技能在共享库里");

        AgentscopeRuntimeAdapter runtime = runtime(enabledSandbox(), store);
        HarnessAgent agent = agentOf(runtime, "alice", "s1");
        Path stagingRoot = WORKSPACE.resolve("sandbox-skills");
        recreate(stagingRoot);

        runtime.prepareSandbox(agent, ctx);

        // ① 本机有一份：框架的投影源只能是本地目录（它不认共享存储），所以先抄下来。
        Path userDir = stagingRoot.resolve("alice");
        assertEquals(
                "技能正文",
                Files.readString(userDir.resolve("skills/demo/SKILL.md")),
                "技能要先抄到本机，容器里才有得投影");

        // ② 这一轮的调用上下文里带着「投影源 = 这个用户的本机目录」。
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        assertNotNull(sandboxContext, "沙箱开着时每轮都要把沙箱上下文交给框架，否则容器里一份技能都没有");
        WorkspaceSpec spec = sandboxContext.getWorkspaceSpec();
        WorkspaceEntry projection = spec.getEntries().get("__workspace_projection__");
        assertTrue(
                projection instanceof WorkspaceProjectionEntry,
                "工作区清单里要有一条投影项，实际是：" + (projection == null ? "没有" : projection.getClass()));
        WorkspaceProjectionEntry entry = (WorkspaceProjectionEntry) projection;
        assertEquals(
                userDir.toAbsolutePath().normalize().toString(),
                entry.getSourceRoot(),
                "投影源必须是这个用户本轮的暂存目录（按人分开，甲看不到乙的技能）");
        assertEquals(
                List.of(SandboxSkillStaging.SKILLS_DIR),
                entry.getIncludeRoots(),
                "只投影技能目录：工作区里别的东西没有进容器的理由");
    }

    /** 反面：沙箱关着的时候，这条路上什么都不该发生（行为与改造前一致）。 */
    @Test
    void 沙箱关着时_不落盘_也不往调用上下文里塞沙箱() throws IOException {
        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        // 注意共享库传 null：不开沙箱时若开了共享库，框架会要求一个分布式状态库（这是 H-04 的约束、
        // 与这条用例无关），而这里要看的只是「沙箱没开时这一步什么都不做」。
        AgentscopeRuntimeAdapter runtime = runtime(SandboxSettings.disabled(), null);
        HarnessAgent agent = agentOf(runtime, "alice", "s1");
        Path stagingRoot = WORKSPACE.resolve("sandbox-skills");
        recreate(stagingRoot);

        runtime.prepareSandbox(agent, ctx);

        assertNull(ctx.get(SandboxContext.class), "没开沙箱就不该有沙箱上下文");
        assertFalse(
                Files.exists(stagingRoot.resolve("alice")),
                "没开沙箱就不该往本机抄技能（这一步只在沙箱模式下有意义）");
    }

    /**
     * 投影的目录名必须与「路由回共享库的前缀」是同一个：都是 {@code skills/}。
     *
     * <p>两处对不上的话会非常难查：模型按提示词里的 {@code /workspace/skills/<技能>} 去跑脚本，
     * 投影却把文件灌到了别的目录（或者反过来，路由的读跟投影的写不是一个地方），
     * 表现都是「技能明明在，就是跑不起来」。
     */
    @Test
    void 投影的目录名与共享前缀是同一个_否则模型读一个地方_容器里跑的是另一个地方() {
        assertEquals(
                List.of(SandboxSkillStaging.SKILLS_DIR + "/"),
                enabledSandbox().sharedPrefixes(),
                "投影目录（SandboxSkillStaging.SKILLS_DIR）与共享前缀必须都是 skills/");
    }

    /** 共享库那一套工作区文件系统（与远端模式同一个形状）：用来往共享库里放/读技能。 */
    private static AbstractFilesystem 工作区(BaseStore store) {
        return new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .toFilesystem(WORKSPACE, "doctor-data-assistant", IsolationScope.USER.toNamespaceFactory());
    }

    /** 清空并重建一个目录（用例要能重复跑）。 */
    private static void recreate(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(dir);
    }
}
