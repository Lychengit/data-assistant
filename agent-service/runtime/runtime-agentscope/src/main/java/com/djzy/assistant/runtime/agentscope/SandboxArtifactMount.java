package com.djzy.assistant.runtime.agentscope;

import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 沙箱「工件目录」（2026-09-27）：把容器里的 {@code <workspaceRoot>/out} 用 bind mount 钉在宿主机的一个目录上。
 *
 * <h2>为什么非有它不可</h2>
 * 技能里有一类流程天生跨轮：「先生在容器里生成文件 → 用户确认 → 再上传」。确认会把一轮拆成两轮，而框架
 * 每轮结束都会把容器 <b>删掉</b>（{@code SandboxManager#release} → {@code DockerSandbox#shutdown} 里的
 * {@code docker stop} + {@code docker rm --force}），下一轮是一台全新的空容器。于是第一轮生成的 xlsx
 * 在第二轮**根本不存在**：2026-09-27 真机实测，模型第二轮找不到文件、只好把 base64 分几次打印出来再手工拼，
 * 拼出来的 xlsx 是坏的（224 字节，真文件 6379 字节），上传成功、链接有效、文件打不开。
 *
 * <p>换掉「容器一轮一个」这条设计不在这个类的职责里（那是容器生命周期的事）。这里做的是另一件更小的事：
 * <b>让产物不落在容器里</b>。容器里写 {@code /workspace/out/perf.xlsx} 时，实际上写的是宿主机上这个目录里的
 * {@code perf.xlsx}——容器删不删都还在，下一轮新容器挂同一个目录照样看得到，宿主机侧也能直接读字节
 * （见 {@code AgentscopeRuntimeAdapter#readSandboxOrHostArtifact}）。
 *
 * <h2>为什么是 bind mount，而不是「每轮把 out/ 打包捞出来」</h2>
 * 框架自带这条路（{@code WorkspaceSpec.entries} 里的 {@code BindMountEntry} → {@code docker run -v}，
 * 连 Windows 路径规范化与「打工作区 tar 时排除挂载点」都做好了）。自己捞的话要在「容器被删之前」挂钩子，
 * 而那个时机在框架的 {@code FluxUsing} 清理里，平台拿不到。能站在框架的地上就别自己造。
 *
 * <h2>按用户分开</h2>
 * 挂载点是 {@code <工件根>/<用户目录>}，用户目录名的算法与 {@link SandboxSkillStaging} 同源
 * （{@link SandboxSkillStaging#dirNameFor}）：甲看不到乙的产物——沙箱本来就是「一个用户一个槽位」（L-21）。
 */
final class SandboxArtifactMount {

    /**
     * 工件目录在容器里的名字。
     *
     * <p>挂在 {@code <workspaceRoot>/out} 上：模型在容器里写 {@code /workspace/out/<文件>} 就落到宿主机。
     * 这个名字是**技能说明书里写死的那个路径**（见技能包的 {@code SKILL.md}：脚本的 {@code --output} 就是
     * {@code /workspace/out/perf.xlsx}）——改这里等于改技能的产出约定，两边必须一起改。
     */
    static final String CONTAINER_DIR = "out";

    private final Path root;

    SandboxArtifactMount(Path root) {
        this.root = root;
    }

    /** 这个用户的工件目录（**会创建**；宿主侧挂载点必须真实存在，否则 docker 会当成要新建的匿名卷）。 */
    Path prepareUserDir(String userId) throws IOException {
        Path dir = userDir(userId);
        Files.createDirectories(dir);
        return dir;
    }

    /** 这个用户的工件目录（只算路径，不碰磁盘：读的那条路不该有副作用）。 */
    Path userDir(String userId) {
        return root.resolve(SandboxSkillStaging.dirNameFor(userId));
    }

    /**
     * 造那条挂载声明。
     *
     * <p>{@code readOnly=false}：脚本要往里写。{@code ephemeral=true}：它是「每轮重新挂上」的东西，
     * 不是要写进容器工作区的内容（框架的 {@code WorkspaceSpecApplier} 按这个标志决定分支 A 里要不要重新应用）。
     */
    BindMountEntry entry(Path userDir) {
        BindMountEntry entry = new BindMountEntry();
        entry.setHostPath(userDir.toAbsolutePath().normalize().toString());
        entry.setReadOnly(false);
        entry.setEphemeral(true);
        return entry;
    }

    /**
     * 把「模型给的沙箱路径」翻成宿主机上的真实文件；**不在工件目录下就返回空**（调用方据此改走容器）。
     *
     * <p>接受两种写法：容器里的绝对路径（{@code /workspace/out/perf.xlsx}，技能说明书里就是这么写的）
     * 与相对工作区根的写法（{@code out/perf.xlsx}）。越界的路径（{@code out/../secrets}）一律拒绝——
     * 这条路径最终是模型给的，不能让它把任意宿主文件读进来。
     */
    Optional<Path> hostPathFor(String userId, String workspaceRoot, String sandboxPath) {
        if (sandboxPath == null || sandboxPath.isBlank()) {
            return Optional.empty();
        }
        String path = sandboxPath.trim().replace('\\', '/');
        String rootPrefix = (workspaceRoot == null || workspaceRoot.isBlank()) ? null : trimSlashes(workspaceRoot);
        if (rootPrefix != null && path.startsWith("/" + rootPrefix + "/")) {
            path = path.substring(rootPrefix.length() + 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!path.startsWith(CONTAINER_DIR + "/")) {
            return Optional.empty();
        }
        String relative = path.substring(CONTAINER_DIR.length() + 1);
        if (relative.isBlank()) {
            return Optional.empty();
        }
        Path userDir = userDir(userId).toAbsolutePath().normalize();
        Path target = userDir.resolve(relative).normalize();
        return target.startsWith(userDir) ? Optional.of(target) : Optional.empty();
    }

    private static String trimSlashes(String path) {
        String trimmed = path.replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
