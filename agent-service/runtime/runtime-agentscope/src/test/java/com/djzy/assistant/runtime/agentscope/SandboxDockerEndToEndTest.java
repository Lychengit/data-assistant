package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * H-05 真容器端到端（**需要 Docker，没有就跳过**）：开沙箱之后，命令、技能、临时文件各自落在该落的地方。
 *
 * <p>前面两个用例验的是「装配长什么样」（{@code SandboxWiringTest}）和「路由语义是什么」
 * （{@code SandboxFilesystemRoutingTest}），它们用的是假沙箱后端，跑得快但不能证明「真的起得来容器」。
 * 这一个补上最后一段，钉三件事：
 *
 * <ol>
 *   <li><b>命令真的在容器里跑</b>——而不是又跑回本机（{@code /.dockerenv} 只有容器里才有）；</li>
 *   <li><b>技能落在共享库</b>——写完另一个「实例」也读得到，而且容器里看不到它（容器只放临时文件；
 *       要让技能脚本在容器里跑，得先把技能投影进容器，那是 H-06b，见 DR-43 / DR-44）；</li>
 *   <li><b>没被路由的路径由容器服务</b>——容器在自己工作区里写下的文件，通过文件系统读得到。</li>
 * </ol>
 *
 * <p>它不跑模型，所以不需要「编一个会调工具的假模型」：真跑一轮时「把沙箱绑给文件系统」这一步是框架的
 * 生命周期中间件做的（每次调用开始 acquire + start、结束 release），这里为了让用例直接驱动文件系统，
 * 自己绑一下（见下面 {@code container.setSandbox(sandbox)} 处的注释）。
 */
class SandboxDockerEndToEndTest {

    /** 与运行时里那个常量一致：共享库的命名空间带 agent 名，名字对不上就"写进去读不出来"。 */
    private static final String AGENT_NAME = "doctor-data-assistant";

    /** 假想的「本机工作目录」：沙箱模式下它只给框架的只读模板层当根，不参与读写。 */
    private static final Path WORKSPACE = Path.of("target", "sandbox-docker-e2e-workspace");

    @Test
    void 命令在真容器里跑_技能落共享库_临时文件留在容器里() throws Exception {
        assumeTrue(DockerCli.available(), "本机没有可用的 Docker（docker version 跑不通），跳过真容器用例");

        SandboxSettings settings = new SandboxSettings(
                true,
                SandboxSettings.DEFAULT_IMAGE,
                SandboxSettings.DEFAULT_WORKSPACE_ROOT,
                null,
                null,
                null,
                SandboxSettings.DEFAULT_SHARED_PREFIXES,
                null);
        BaseStore store = new InMemoryStore();
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("s1").build();

        // 装配成**和 HarnessAgent.Builder 在「开沙箱 + 有共享库」时一样的对象图**（形状由 SandboxWiringTest 守着）：
        // 容器当主文件系统 + skills/ 前缀路由回共享库。
        SandboxContext sandboxContext = settings.toFilesystemSpec().toSandboxContext(WORKSPACE);
        DockerSandboxClientOptions options = (DockerSandboxClientOptions) sandboxContext.getClientOptions();
        SandboxClient<DockerSandboxClientOptions> client = options.createClient();
        Sandbox sandbox = client.create(sandboxContext.getWorkspaceSpec(), sandboxContext.getSnapshotSpec(), options);

        SandboxBackedFilesystem container = new SandboxBackedFilesystem();
        RoutedSandboxFilesystem filesystem = new RoutedSandboxFilesystem(
                container,
                SandboxSharedRoutes.build(store, WORKSPACE, AGENT_NAME, IsolationScope.USER, settings.sharedPrefixes()));

        try {
            sandbox.start();
            // 这一句在真跑一轮时**不用我们自己写**：框架的 SandboxLifecycleMiddleware 会在调用开始时
            // acquire + start、绑到这次调用的 RuntimeContext 上，结束时 release。用例不跑模型，
            // 所以在这里手动把沙箱绑上（binding 是框架的用法，见 SandboxBackedFilesystem 的类注释）。
            container.setSandbox(sandbox);

            // ① 命令真的在容器里跑：/.dockerenv 只有容器里才有，本机（Windows 宿主）不会有。
            ExecuteResponse probe = filesystem.execute(alice, "test -f /.dockerenv && echo IN_CONTAINER", 60);
            assertTrue(probe.isSuccess(), "命令要在容器里跑成功，实际：" + probe.output());
            assertTrue(
                    probe.output().contains("IN_CONTAINER"),
                    "命令必须是跑在容器里（/.dockerenv 存在），而不是又跑回本机；实际：" + probe.output());

            // ② 技能走共享库：无前导斜杠是**技能下发的口径**（WorkspaceSkillProvisioner 就是这么写的），
            //    带前导斜杠是**框架文件接口的契约口径**；两种写法必须落到同一个文件上。
            assertTrue(
                    filesystem.write(alice, "skills/demo/SKILL.md", "技能正文").isSuccess(),
                    "技能要能写进共享库（多副本共享是 H-06a 的前提）");
            assertEquals(
                    "技能正文",
                    filesystem.read(alice, "/skills/demo/SKILL.md", 0, 100).fileData().content(),
                    "换一种写法（带前导斜杠）读，也要读到同一份：两种口径不能是两个文件");
            assertEquals(
                    "技能正文",
                    另一台实例(store).read(alice, "skills/demo/SKILL.md", 0, 100).fileData().content(),
                    "另一台实例（另建一个共享库文件系统）也必须读到同一份——这才叫「技能跨实例共享」");

            // 反面：共享库里的技能**不该**出现在容器里。容器只放临时文件；
            // 要让技能脚本在容器里跑，得先把技能投影进容器，那是 H-06b（DR-43 / DR-44）。
            ExecuteResponse containerView = filesystem.execute(
                    alice,
                    "if [ -e /skills/demo/SKILL.md ] || [ -e /demo/SKILL.md ]; then echo SKILL_IN_CONTAINER;"
                            + " else echo SKILL_NOT_IN_CONTAINER; fi",
                    60);
            assertTrue(
                    containerView.output().contains("SKILL_NOT_IN_CONTAINER"),
                    "共享库里的技能不该出现在容器里（容器里只有临时文件），实际：" + containerView.output());

            // ③ 没被路由的路径由容器服务：让**容器自己**在工作区里写一个文件，再通过文件系统读回来。
            //    为什么不写成 filesystem.write(...)：框架的 write 会发一条带 mkdir -p "$(dirname ...)" 的命令，
            //    而 Windows 宿主上的 docker CLI 会把双引号分组吃掉（台账 L-19），这条命令在容器里变成语法错误。
            //    那是本机开发环境的坑、不是沙箱这条路的问题（Linux 宿主正常），所以这里避开它、照样把语义钉住：
            //    读得到就说明这条路径由容器服务（要是被路由去了共享库，这份文件根本不存在）。
            //    内容刻意用 ASCII：命令字符串要经 docker CLI 进容器，中文的编码不如共享库那条路稳。
            ExecuteResponse created = filesystem.execute(
                    alice, "mkdir -p scratch && echo CONTAINER_LOCAL_FILE > scratch/tmp.txt && cat scratch/tmp.txt", 60);
            assertTrue(created.isSuccess(), "容器里要能建文件与目录，实际：" + created.output());
            assertEquals(
                    "CONTAINER_LOCAL_FILE",
                    filesystem.read(alice, "scratch/tmp.txt", 0, 100).fileData().content(),
                    "没被路由的路径必须由容器服务：容器自己写的文件，通过文件系统要读得到"
                            + "（读的是容器的工作区，工作目录就是工作区根，所以相对路径对得上）");
        } finally {
            sandbox.close();
        }

        assertFalse(DockerCli.leftoverTestContainers(), "用完别在机器上留容器");
    }

    /**
     * 「另一台实例」：同一套共享库、同一个 agent 名，另起一个文件系统对象。
     *
     * <p>这一步是「跨实例」的全部意义所在——如果键位和沙箱路由那套对不上，这里就会读不到。
     */
    private static AbstractFilesystem 另一台实例(BaseStore store) {
        return new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .toFilesystem(WORKSPACE, AGENT_NAME, IsolationScope.USER.toNamespaceFactory());
    }

    /**
     * H-06b 真容器端到端：**技能投影进容器，技能里的脚本在容器里跑得动，而且下一轮容器重建后还在**。
     *
     * <p>三条断言对应三件事：
     * <ol>
     *   <li>第一轮能跑——技能从共享库抄到本机、再由框架投影进容器，这条链子是通的；</li>
     *   <li>第二轮（容器已被删掉、重建了一台空的）**不清**上一轮沙箱状态时看不到技能——这是框架 2.0.3 的行为：
     *       它按内容哈希认为「投影做过一次」，于是跳过投影（台账 L-20）；</li>
     *   <li>清掉上一轮状态之后再开一轮，技能又在了——这就是适配器里那一步清理存在的理由
     *       （{@code AgentscopeRuntimeAdapter#clearStaleSandboxState}）。</li>
     * </ol>
     *
     * <p>第 2 条看着像在钉别人的 bug，其实是**钉我们自己的假设**：哪天框架改了（容器重建后一定会重新投影），
     * 它会失败，那时就可以把那步清理删掉。
     */
    @Test
    void 技能投影进容器_脚本在容器里跑得动_而且下一轮容器重建后还在() throws Exception {
        assumeTrue(DockerCli.available(), "本机没有可用的 Docker（docker version 跑不通），跳过真容器用例");

        SandboxSettings settings = new SandboxSettings(
                true,
                SandboxSettings.DEFAULT_IMAGE,
                SandboxSettings.DEFAULT_WORKSPACE_ROOT,
                null,
                null,
                null,
                SandboxSettings.DEFAULT_SHARED_PREFIXES,
                null);
        BaseStore store = new InMemoryStore();
        RuntimeContext alice = RuntimeContext.builder().userId("alice").sessionId("s1").build();

        // 共享库里的技能（等价于 H-06a 下发完的样子）：一份说明书 + 一个脚本。
        AbstractFilesystem shared = 另一台实例(store);
        assertTrue(shared.write(alice, "skills/demo/SKILL.md", "技能正文").isSuccess(), "前提：技能在共享库里");
        assertTrue(
                shared.uploadFiles(
                                alice,
                                List.of(Map.entry(
                                        "skills/demo/scripts/run.sh",
                                        "echo HELLO_FROM_SKILL\n".getBytes(StandardCharsets.UTF_8))))
                        .get(0)
                        .isSuccess(),
                "前提：脚本在共享库里");

        // 平台每一轮的装配（与 AgentscopeRuntimeAdapter#prepareSandbox 同一套调用）：
        // ① 技能抄到本机；② 用这份当投影源交给框架。
        Path stagingRoot = WORKSPACE.resolve("sandbox-skills");
        recreate(stagingRoot);
        Path userDir = new SandboxSkillStaging(stagingRoot).stage(shared, alice);
        SandboxContext sandboxContext = settings.toProjectingFilesystemSpec().toSandboxContext(userDir);

        DockerSandboxClientOptions options = (DockerSandboxClientOptions) sandboxContext.getClientOptions();
        AgentStateStore stateStore = new InMemoryAgentStateStore();
        SandboxManager manager =
                new SandboxManager(new DockerSandboxClient(), new SessionSandboxStateStore(stateStore, AGENT_NAME), AGENT_NAME);

        // ① 第一轮：容器里真有这份脚本，而且真跑得动（跑的是容器里的文件，不是共享库那份）。
        assertEquals(
                "HELLO_FROM_SKILL",
                跑一轮(manager, sandboxContext, alice, "sh /workspace/skills/demo/scripts/run.sh"),
                "技能投影进容器之后，技能里的脚本要能在容器里直接跑起来");

        // ② 第二轮：容器在上一轮结束时已经被删掉了，这是一台全新的空容器；
        //    不清状态的话，框架按哈希跳过投影（技能不在）。
        assertFalse(
                容器里有技能脚本(manager, sandboxContext, alice),
                "框架 2.0.3 在容器重建后仍按内容哈希跳过投影：不清上一轮的沙箱状态，第二轮就看不到技能");

        // ③ 清掉上一轮的沙箱状态（适配器每轮开工前就是这么做的），再开一轮：技能又在了。
        clearStaleSandboxState(stateStore, alice);
        assertEquals(
                "HELLO_FROM_SKILL",
                跑一轮(manager, sandboxContext, alice, "sh /workspace/skills/demo/scripts/run.sh"),
                "清掉上一轮的沙箱状态之后，框架会重新投影，第二轮起的容器里也要跑得动技能脚本");

        assertFalse(DockerCli.leftoverTestContainers(), "用完别在机器上留容器");
    }

    /**
     * 跑一轮：与框架的生命周期中间件同样的一串动作
     * （acquire → start → 执行 → persistState → release）。
     */
    private static String 跑一轮(
            SandboxManager manager, SandboxContext ctx, RuntimeContext runtimeCtx, String command) throws Exception {
        SandboxAcquireResult result = manager.acquire(ctx, runtimeCtx);
        try {
            result.getSandbox().start();
            ExecResult exec = result.getSandbox().exec(RuntimeContext.empty(), command, 60);
            assertTrue(exec.ok(), "命令要在容器里跑成功，实际：" + exec.combinedOutput());
            return exec.combinedOutput().trim();
        } finally {
            manager.persistState(result, ctx, runtimeCtx);
            // 这一句就是「本轮结束」：框架会 stop 容器再把它删掉（所以下一轮是全新的空容器）。
            manager.release(result);
            result.getLease().close();
        }
    }

    /** 容器里还有没有这份技能脚本（文件不在时 docker exec 以非 0 退出，框架会抛出来）。 */
    private static boolean 容器里有技能脚本(
            SandboxManager manager, SandboxContext ctx, RuntimeContext runtimeCtx) throws Exception {
        SandboxAcquireResult result = manager.acquire(ctx, runtimeCtx);
        try {
            result.getSandbox().start();
            try {
                result.getSandbox().exec(RuntimeContext.empty(), "cat /workspace/skills/demo/scripts/run.sh", 60);
                return true;
            } catch (Exception e) {
                return false;
            }
        } finally {
            manager.persistState(result, ctx, runtimeCtx);
            manager.release(result);
            result.getLease().close();
        }
    }

    /** 适配器每轮开工前做的那一步：清掉上一轮的沙箱状态（见 {@code clearStaleSandboxState}）。 */
    private static void clearStaleSandboxState(AgentStateStore stateStore, RuntimeContext runtimeCtx)
            throws IOException {
        Optional<SandboxIsolationKey> key = SandboxIsolationKey.resolve(IsolationScope.USER, runtimeCtx, AGENT_NAME);
        if (key.isPresent()) {
            new SessionSandboxStateStore(stateStore, AGENT_NAME).delete(key.get());
        }
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
