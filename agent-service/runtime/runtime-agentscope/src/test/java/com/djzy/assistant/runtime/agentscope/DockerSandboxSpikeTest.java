package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H-05 选型验证（**真起容器**那一段）：工作区能不能「投影」进沙箱、命令是不是真在容器里跑。
 *
 * <p>上一个用例（{@code SandboxFilesystemRoutingTest}）用假后端钉住了路由语义。
 * 这一个补的是它验不到的部分：
 *
 * <ol>
 *   <li>投影的来源能不能由我们自己指定——框架默认把投影源钉在「本机工作目录」上，
 *       而我们的技能正文其实在 PG 里，所以要确认能换成我们自己的 {@link WorkspaceSpec}；
 *   <li>命令是真的落在容器里，而不是又跑回本机；
 *   <li>投影进去的文件，容器里真的读得到（技能里的脚本要能在容器里闭环执行）。
 * </ol>
 *
 * <p>本机没有可用的 Docker 时**跳过**（{@code assumeTrue}）：这不是「测试通过」，而是「没有可验证的环境」。
 */
class DockerSandboxSpikeTest {

    /** 测试用的落地目录：按仓库惯例放 target 下，别用系统临时目录。 */
    private static final Path STAGING = Path.of("target", "docker-sandbox-spike");

    @Test
    void 命令真的在容器里跑_而且投影进去的技能脚本容器里读得到() throws Exception {
        assumeTrue(DockerCli.available(), "本机没有可用的 Docker（docker version 跑不通），跳过真容器用例");

        // 1) 准备「要投影进容器的东西」：一个技能脚本 + 一个 AGENTS.md。
        //    真实情况下这些内容来自 PG（管理端发布的技能包），这里只关心「投影这条路通不通」。
        recreateStaging();
        Files.writeString(STAGING.resolve("AGENTS.md"), "项目约定：先说结论。", StandardCharsets.UTF_8);
        Path skillDir = Files.createDirectories(STAGING.resolve("skills").resolve("demo"));
        Files.writeString(
                skillDir.resolve("run.sh"), "echo HELLO_FROM_SKILL" + System.lineSeparator(), StandardCharsets.UTF_8);

        // 2) 关掉框架默认投影（它只会照本机工作目录扫），换成我们自己的投影来源。
        DockerFilesystemSpec spec = new DockerFilesystemSpec().image("ubuntu:22.04").workspaceRoot("/workspace");
        spec.workspaceProjectionEnabled(false);
        spec.workspaceSpec(投影(STAGING.toString(), List.of("AGENTS.md", "skills")));

        SandboxContext context = spec.toSandboxContext(STAGING);
        DockerSandboxClientOptions options = (DockerSandboxClientOptions) context.getClientOptions();
        SandboxClient<DockerSandboxClientOptions> client = options.createClient();
        Sandbox sandbox = client.create(context.getWorkspaceSpec(), context.getSnapshotSpec(), options);

        try {
            sandbox.start();

            ExecResult result =
                    sandbox.exec(
                            RuntimeContext.empty(),
                            "test -f /.dockerenv && echo IN_CONTAINER; echo ---; cat /workspace/skills/demo/run.sh",
                            120);

            assertTrue(result.ok(), "命令要能在容器里跑成功，实际：" + result.combinedOutput());
            assertTrue(
                    result.combinedOutput().contains("IN_CONTAINER"),
                    "命令必须是跑在容器里（/.dockerenv 存在），而不是又跑回本机；实际：" + result.combinedOutput());
            assertTrue(
                    result.combinedOutput().contains("HELLO_FROM_SKILL"),
                    "投影进去的技能脚本，容器里要读得到——技能脚本要能在容器里闭环执行；实际：" + result.combinedOutput());
        } finally {
            sandbox.close();
        }

        assertFalse(DockerCli.available() && DockerCli.leftoverTestContainers(), "用完别在机器上留容器");
    }

    private static WorkspaceSpec 投影(String sourceRoot, List<String> includeRoots) {
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(sourceRoot);
        projection.setIncludeRoots(includeRoots);

        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
        entries.put("__workspace_projection__", projection);

        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setEntries(entries);
        return workspaceSpec;
    }

    private static void recreateStaging() throws IOException {
        if (Files.exists(STAGING)) {
            try (var walk = Files.walk(STAGING)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(STAGING);
    }
}
