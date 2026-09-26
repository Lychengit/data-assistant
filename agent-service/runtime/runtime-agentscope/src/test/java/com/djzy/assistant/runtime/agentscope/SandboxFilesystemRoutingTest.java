package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H-05 选型验证：**沙箱当主、PG 当路由**这条路，框架到底认不认、语义是什么。
 *
 * <p>我们的目标是一句话：「读写走 PG（多实例共享）、跑脚本走容器（隔离）」。
 * 框架的 {@link RoutedSandboxFilesystem} 声称支持这个形状，但有三个点只有真跑一遍才知道，
 * 写错任何一个整个设计都要返工：
 *
 * <ol>
 *   <li>跑命令会不会因为路径被路由走而落到 PG 那边——那样隔离就没了；
 *   <li>命中前缀的读写到底落在哪一边；
 *   <li>前缀的匹配规则——特别是「把前缀写成单个斜杠、整个工作区一把梭」行不行。
 * </ol>
 *
 * <p>这里用一个**假的沙箱后端**（只在内存里记账）来钉住这些语义，所以不需要 Docker，CI 里可以常跑。
 */
class SandboxFilesystemRoutingTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @Test
    void 跑命令永远落在沙箱后端_哪怕这个路径已经被路由走了() {
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RemoteFilesystem pgLike = remoteStore();
        AbstractFilesystem fs = new RoutedSandboxFilesystem(sandbox, Map.of("/skills/", pgLike));

        ExecuteResponse response = ((AbstractSandboxFilesystem) fs).execute(CTX, "bash /skills/demo/run.sh", 10);

        assertTrue(response.isSuccess(), "命令要能跑成功");
        assertEquals(
                List.of("bash /skills/demo/run.sh"),
                sandbox.executedCommands,
                "命令必须落在沙箱后端上——这是脚本隔离的全部意义；跑到 PG 那边执行就等于没有隔离");
    }

    @Test
    void 命中前缀的读写落到路由后端_沙箱里看不到这份文件() {
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RemoteFilesystem pgLike = remoteStore();
        AbstractFilesystem fs = new RoutedSandboxFilesystem(sandbox, Map.of("/skills/", pgLike));

        assertTrue(fs.write(CTX, "/skills/demo/SKILL.md", "技能正文").isSuccess());

        assertEquals(
                "技能正文",
                fs.read(CTX, "/skills/demo/SKILL.md", 0, 100).fileData().content(),
                "命中 /skills/ 前缀，读写都该走路由后端（PG）");
        assertFalse(
                sandbox.files.containsKey("/skills/demo/SKILL.md"),
                "这份文件在沙箱里不该存在：共享的那份由 PG 管，沙箱只是执行场所");
    }

    @Test
    void 没命中前缀的读写留在沙箱里() {
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RemoteFilesystem pgLike = remoteStore();
        AbstractFilesystem fs = new RoutedSandboxFilesystem(sandbox, Map.of("/skills/", pgLike));

        assertTrue(fs.write(CTX, "/scratch/tmp.txt", "临时文件").isSuccess());

        assertTrue(
                sandbox.files.containsKey("/scratch/tmp.txt"),
                "没被路由的路径就是沙箱自己的地盘（比如跑脚本时产生的中间文件）");
        assertFalse(
                pgLike.read(CTX, "/scratch/tmp.txt", 0, 10).isSuccess(),
                "没被路由的路径不该跑到 PG 里去");
    }

    @Test
    void 路由前缀会被剥掉_对面后端看到的是剥掉之后的路径() {
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RemoteFilesystem pgLike = remoteStore();
        AbstractFilesystem fs = new RoutedSandboxFilesystem(sandbox, Map.of("/skills/", pgLike));

        fs.write(CTX, "/skills/demo/SKILL.md", "技能正文");

        assertEquals(
                "技能正文",
                pgLike.read(CTX, "/demo/SKILL.md", 0, 100).fileData().content(),
                "前缀只用于路由，对面后端拿到的是剥掉前缀的 /demo/SKILL.md；写库时按这个口径，别按逻辑路径");
    }

    @Test
    void 前缀写成单个斜杠等于没写_什么都匹配不到() {
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RemoteFilesystem pgLike = remoteStore();
        CompositeFilesystem fs = new CompositeFilesystem(sandbox, Map.of("/", pgLike));

        assertEquals(
                sandbox,
                fs.filesystemFor("/skills/demo/SKILL.md"),
                "把前缀写成 / 一把梭走不通：框架内部会把两侧的开头斜杠都剥掉，"
                        + "空前缀就只剩精确等于 / 这一种匹配，其它路径都落不到它头上；要路由就得把前缀一个个列出来");
    }

    @Test
    void 前缀按目录边界匹配_不会把同前缀开头的别的目录吞进来() {
        FakeSandboxFilesystem sandbox = new FakeSandboxFilesystem();
        RemoteFilesystem pgLike = remoteStore();
        CompositeFilesystem fs = new CompositeFilesystem(sandbox, Map.of("/skills/", pgLike));

        assertEquals(pgLike, fs.filesystemFor("/skills/demo/SKILL.md"));
        assertEquals(
                sandbox,
                fs.filesystemFor("/skillsX/demo.md"),
                "前缀要带结尾斜杠按目录匹配，/skills/ 不该把 /skillsX/ 也吞进来");
    }

    private static RemoteFilesystem remoteStore() {
        return new RemoteFilesystem(new InMemoryStore(), List.of("alice"));
    }

}
