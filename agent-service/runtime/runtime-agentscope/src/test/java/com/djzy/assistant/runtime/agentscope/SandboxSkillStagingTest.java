package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 技能落盘（H-06b）的守护用例：**容器要用的那份技能，是怎么从共享库摆到本机目录上的**。
 *
 * <p>为什么值得单独测：这一步错了不会报错，只会「容器里少一个脚本」——模型照着框架提示词里那个
 * 路径去跑，跑不到，然后给用户一句"这个技能用不了"。它同时也是**多租户隔离**的一道门：
 * 落盘目录按用户分，甲的身份不能落到乙的目录里（所以身份里那些奇怪字符要单独处理）。
 *
 * <p>不碰 Docker：这条用例只看"本机目录摆得对不对"，真容器里读不读得到由
 * {@code SandboxDockerEndToEndTest} 兜。
 */
class SandboxSkillStagingTest {

    /** 与运行时里那个常量一致：共享库的命名空间里带 agent 名。 */
    private static final String AGENT_NAME = "doctor-data-assistant";

    private static final Path WORKSPACE = Path.of("target", "sandbox-skill-staging-workspace");
    private static final Path STAGING = Path.of("target", "sandbox-skill-staging");

    private BaseStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryStore();
        recreate(STAGING);
    }

    @Test
    void 技能按用户抄到本机_共享库里删掉的本地那份也要没() throws IOException {
        AbstractFilesystem aliceWorkspace = workspaceOf("alice");
        AbstractFilesystem bobWorkspace = workspaceOf("bob");
        RuntimeContext alice = ctx("alice");
        RuntimeContext bob = ctx("bob");

        aliceWorkspace.write(alice, "skills/demo/SKILL.md", "技能正文");
        aliceWorkspace.uploadFiles(alice, List.of(
                Map.entry("skills/demo/scripts/run.sh", "echo HELLO".getBytes(StandardCharsets.UTF_8))));
        bobWorkspace.write(bob, "skills/other/SKILL.md", "别人的技能");

        SandboxSkillStaging staging = new SandboxSkillStaging(STAGING);

        // ① 抄到本机：内容一个字不差，目录结构照旧（脚本的相对路径在容器里要能对上）。
        Path aliceDir = staging.stage(aliceWorkspace, alice);
        assertEquals(STAGING.resolve("alice"), aliceDir, "落盘目录要按用户分，名字就是他的身份");
        assertEquals("技能正文", Files.readString(aliceDir.resolve("skills/demo/SKILL.md")));
        assertEquals("echo HELLO", Files.readString(aliceDir.resolve("skills/demo/scripts/run.sh")));
        assertTrue(Files.isDirectory(aliceDir.resolve("skills")), "投影源下面要有 skills/，框架按它算相对路径");

        // ② 互不串门：甲这份里不能有乙的技能（反过来的多租户泄漏就是从这类小地方来的）。
        assertFalse(Files.exists(aliceDir.resolve("skills/other")), "甲的本机目录里不该出现乙的技能");

        Path bobDir = staging.stage(bobWorkspace, bob);
        assertEquals(STAGING.resolve("bob"), bobDir);
        assertTrue(Files.exists(bobDir.resolve("skills/other/SKILL.md")));
        assertFalse(Files.exists(bobDir.resolve("skills/demo")), "乙的本机目录里不该出现甲的技能");

        // ③ 授权被收走（共享库里删掉）：本机那份也要跟着没，否则容器里还留着上一次的技能。
        aliceWorkspace.delete(alice, "skills/demo/SKILL.md");
        aliceWorkspace.delete(alice, "skills/demo/scripts/run.sh");
        staging.stage(aliceWorkspace, alice);
        assertFalse(Files.exists(aliceDir.resolve("skills/demo/SKILL.md")),
                "共享库里没有的技能，本机那份必须删掉——不然容器里会跑上一轮的旧脚本");
        assertFalse(Files.exists(aliceDir.resolve("skills/demo/scripts/run.sh")),
                "同一批文件里的脚本也要一起删");
    }

    @Test
    void 身份里有特殊字符时_不会互相撞到同一个目录_也跳不出暂存根() throws IOException {
        SandboxSkillStaging staging = new SandboxSkillStaging(STAGING);

        Path at = staging.stage(workspaceOf("alice@corp.com"), ctx("alice@corp.com"));
        Path hash = staging.stage(workspaceOf("alice#corp.com"), ctx("alice#corp.com"));
        assertNotEquals(at, hash, "只会被换成同一种下划线的两个身份，必须靠摘要区分开（否则甲看到乙的技能）");

        Path evil = staging.stage(workspaceOf("../../etc"), ctx("../../etc"));
        assertTrue(evil.normalize().startsWith(STAGING.normalize()),
                "身份里带 .. 也不能落盘到暂存根之外，实际：" + evil);
        assertFalse(evil.equals(STAGING), "更不能直接落到暂存根上（那就和别的用户混在一起了）");
    }

    @Test
    void 没有技能时不报错_也不凭空造出技能目录() throws IOException {
        SandboxSkillStaging staging = new SandboxSkillStaging(STAGING);
        Path userDir = staging.stage(workspaceOf("carol"), ctx("carol"));
        assertFalse(Files.exists(userDir.resolve("skills")),
                "一个技能都没有时不该造出空的 skills/ 目录：容器里因此就是干净的，不会让模型以为有技能");
    }

    private AbstractFilesystem workspaceOf(String userId) {
        return new RemoteFilesystemSpec(store)
                .isolationScope(IsolationScope.USER)
                .toFilesystem(WORKSPACE, AGENT_NAME, IsolationScope.USER.toNamespaceFactory());
    }

    private static RuntimeContext ctx(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("s1").build();
    }

    private static void recreate(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(dir);
    }
}
