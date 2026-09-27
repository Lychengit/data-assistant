package com.djzy.assistant.runtime.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能落盘（H-06b）：把共享库里的 {@code skills/} 抄一份到**这台机器的本地目录**，
 * 好让框架的工作区投影把它灌进容器。
 *
 * <h2>为什么还要抄一份，不能直接投影共享库</h2>
 * 框架的投影（{@code WorkspaceProjectionEntry}）只认「宿主机上的一个目录」——
 * 它本来就是给「本机工作目录」设计的（{@code WorkspaceProjectionEntry#setSourceRoot} 收的是本地路径），
 * 不认共享存储。所以照框架给的用法走：先在本地把内容摆好，启动容器时框架再把这份内容打 tar 灌进去。
 * 框架自己给「中心化技能」用的 {@code MarketplaceStager} 也是这么干的（摆到本机 {@code .skills-cache/} 再投影）。
 *
 * <h2>每台实例各抄各的</h2>
 * 暂存目录是本机磁盘，不共享——这没关系：**内容的权威始终是共享库**，每台实例每轮都从共享库重抄，
 * 抄出来的东西自然一样。这一点与「工作区必须共享」不冲突：共享的是内容，本地那份只是给容器用的副本。
 *
 * <h2>为什么要「每轮」抄</h2>
 * 技能的可见性来自网关授权，随时可能被收走（H-06a 的下发也是每轮跑一次）；
 * 而容器里的副本只在容器活着的时候存在（容器一轮一个，见 {@link SandboxSettings} 的说明）。
 * 所以每轮都要把「这一轮工作区里是什么」重新同步到本地。
 *
 * <p><b>同步而不是重建</b>：只写内容变了的文件、只删共享库里已经没有的文件。
 * 这样同一个用户的两次并发调用同时落到这个目录时，不会读到「删了一半」的中间状态
 * （内容本来就一样，撞上了也只是互相覆盖同名文件）。
 */
final class SandboxSkillStaging {

    private static final Logger log = LoggerFactory.getLogger(SandboxSkillStaging.class);

    /**
     * 技能都放在工作区的这个目录下。
     *
     * <p><b>这个字符串是三处约定的名字</b>，必须一致，否则就是「模型读得到、容器里跑不到」：
     * ① 框架的工作区技能仓库（{@code WorkspaceSkillRepository} 默认扫 {@code skills/<名>/SKILL.md}）；
     * ② 平台下发技能时写的目录（{@code WorkspaceSkillProvisioner.SKILLS_DIR}）；
     * ③ 沙箱模式下路由回共享库的前缀（{@link SandboxSettings#DEFAULT_SHARED_PREFIXES}）。
     * 用例 {@code SandboxWiringTest} 钉住了 ①③ 与它一致。
     */
    static final String SKILLS_DIR = "skills";

    /** 拿不到用户身份时的落盘目录名（框架此时会退回按会话隔离，这里给个稳定的兜底名字）。 */
    private static final String ANONYMOUS = "_anonymous";

    private final Path root;

    SandboxSkillStaging(Path root) {
        this.root = root;
    }

    /**
     * 把共享库里这个用户的 {@code skills/} 同步到本机，返回**这个用户的暂存目录**。
     *
     * <p>返回值就是框架投影的 {@code sourceRoot}：它下面有一份 {@code skills/}，
     * 所以框架投影时会按 {@code skills/...} 的相对路径灌进容器的工作区。
     *
     * <p><b>失败不抛</b>：技能是增强不是主链路。抄不下来就让这一轮照常跑（容器里没有技能脚本，
     * 技能正文照旧读得到——读的是共享库那份），失败写日志。
     *
     * @param workspace 这个用户的工作区文件系统（沙箱模式下是「路由版」：{@code skills/} 走共享库）
     * @param ctx 这一轮的运行上下文（取 userId；命名空间由框架按它解析）
     */
    Path stage(AbstractFilesystem workspace, RuntimeContext ctx) {
        Path userDir = root.resolve(dirNameFor(ctx == null ? null : ctx.getUserId()));
        Path skillsDir = userDir.resolve(SKILLS_DIR);
        try {
            Map<String, byte[]> wanted = readSkills(workspace, ctx);
            mirror(skillsDir, wanted);
            log.debug("技能已落盘待投影：userId={} 文件={} 目录={}",
                    ctx == null ? null : ctx.getUserId(), wanted.size(), userDir);
        } catch (Exception e) {
            log.warn("技能落盘失败（这一轮容器里没有技能脚本，技能正文照旧读得到）：userId={} 原因={}",
                    ctx == null ? null : ctx.getUserId(), e.toString());
        }
        return userDir;
    }

    /**
     * 从共享库读这个用户 {@code skills/} 下的所有文件（相对路径 → 字节）。
     *
     * <p>路径口径与技能下发一致：**不带前导斜杠**（{@code skills/demo/SKILL.md}）。
     * 框架的 {@code ls} 一次给一层（文件 + 子目录），所以要自己递归下去。
     */
    private static Map<String, byte[]> readSkills(AbstractFilesystem workspace, RuntimeContext ctx) {
        List<String> paths = new ArrayList<>();
        collect(workspace, ctx, SKILLS_DIR, paths);
        if (paths.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        // 一把取回（框架的文件接口本来就收一串路径），二进制资源也在这里原样拿到。
        for (FileDownloadResponse response : workspace.downloadFiles(ctx, paths)) {
            if (response.isSuccess() && response.content() != null) {
                files.put(relativeToSkills(response.path()), response.content());
            } else {
                log.warn("技能文件取不出来（这一份不会进容器）：path={} 原因={}", response.path(), response.error());
            }
        }
        return files;
    }

    /** 递归收集一个目录下的所有文件路径（目录继续往下走）。 */
    private static void collect(AbstractFilesystem workspace, RuntimeContext ctx, String dir, List<String> out) {
        LsResult listed = workspace.ls(ctx, dir);
        if (!listed.isSuccess() || listed.entries() == null) {
            if (!listed.isSuccess() && dir.equals(SKILLS_DIR)) {
                // 这个用户一个技能都没有：正常情况，不是错误。
                log.debug("工作区里没有 {} 目录（这个用户当前没有技能）：原因={}", SKILLS_DIR, listed.error());
            }
            return;
        }
        for (FileInfo entry : listed.entries()) {
            String path = entry.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = trimTrailingSlash(path);
            if (entry.isDirectory()) {
                collect(workspace, ctx, normalized, out);
            } else {
                out.add(normalized);
            }
        }
    }

    /**
     * 把本机那份同步成「共享库那份的样子」：内容变了的重写，共享库里已经没有的删掉。
     *
     * <p>空目录不用管：框架投影只收**文件**（{@code WorkspaceProjectionApplier} 里过滤了
     * {@code isRegularFile}），空目录进不了容器。
     */
    private static void mirror(Path skillsDir, Map<String, byte[]> wanted) throws IOException {
        if (Files.isDirectory(skillsDir)) {
            Set<String> present = new LinkedHashSet<>();
            try (var walk = Files.walk(skillsDir)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    String relative = skillsDir.relativize(path).toString().replace('\\', '/');
                    if (wanted.containsKey(relative)) {
                        present.add(relative);
                    } else {
                        Files.deleteIfExists(path);
                    }
                }
            }
            write(skillsDir, wanted, present);
        } else {
            write(skillsDir, wanted, Set.of());
        }
    }

    /** 写入「本地没有的」与「内容不一样的」（内容一样的就不碰，省得每轮都在动同一批文件）。 */
    private static void write(Path skillsDir, Map<String, byte[]> wanted, Set<String> present) throws IOException {
        for (Map.Entry<String, byte[]> file : wanted.entrySet()) {
            Path target = skillsDir.resolve(file.getKey()).normalize();
            if (!target.startsWith(skillsDir)) {
                // 路径从共享库来（技能包里的相对路径），不该出现越界；真出现了就别写出这个目录。
                log.warn("技能文件路径越界，跳过：{}", file.getKey());
                continue;
            }
            if (present.contains(file.getKey()) && sameContent(target, file.getValue())) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
    }

    private static boolean sameContent(Path target, byte[] expected) {
        try {
            return Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), expected);
        } catch (IOException e) {
            return false;   // 读不出来就当不一样，重写一遍（写失败会在上层记日志）
        }
    }

    /** 去掉 {@code skills/} 前缀：共享库给的是 {@code skills/demo/run.sh}，落盘要的是 {@code demo/run.sh}。 */
    private static String relativeToSkills(String workspacePath) {
        String normalized = trimTrailingSlash(workspacePath == null ? "" : workspacePath.replace('\\', '/'));
        String prefix = SKILLS_DIR + "/";
        return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
    }

    private static String trimTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * 用户身份 → 目录名：**互不相同的身份不能落到同一个目录**（否则甲会看到乙的技能）。
     *
     * <p>只留「字母 / 数字 / 下划线 / 中划线」这类放心的字符；一旦这个身份里有别的字符（点、@、斜杠…），
     * 就再补一段身份摘要把它区分开——只做字符替换是不够的：{@code alice@corp.com} 与
     * {@code alice#corp.com} 都会被换成同一个 {@code alice_corp_com}。顺手也把 {@code ..}
     * 这类能跳出目录的名字挡掉（点不在允许集合里）。
     *
     * <p>框架自己的 {@code MarketplaceStager} 处理同一个问题用的是同一套办法。
     *
     * <p>包内共用（2026-09-27 起）：沙箱的工件目录（{@link SandboxArtifactMount}）按同一个用户
     * 也落在这个命名规则下，两处必须同源——否则「技能暂存在 alice/、工件却写到 alice-3f2a/」这种
     * 错配在日志里看不出来。
     */
    static String dirNameFor(String userId) {
        String identity = (userId == null || userId.isBlank()) ? ANONYMOUS : userId;
        String usable = identity.replaceAll("[^A-Za-z0-9_-]", "_");
        boolean lossless = !usable.isBlank() && usable.equals(identity) && usable.length() <= 64;
        return lossless ? usable : usable + "-" + digest(identity).substring(0, 12);
    }

    private static String digest(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hashed = sha.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // 只可能发生在「本机没有 SHA-256」这种不可能的情况；退回长度，至少不会撞成同一个目录。
            return String.format("%012x", (long) text.hashCode());
        }
    }
}
