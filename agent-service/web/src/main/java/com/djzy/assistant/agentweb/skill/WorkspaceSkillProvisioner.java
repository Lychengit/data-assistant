package com.djzy.assistant.agentweb.skill;

import com.djzy.assistant.common.skill.PackageContent;
import com.djzy.assistant.common.skill.SkillManifest;
import com.djzy.assistant.runtime.agentscope.SkillProvisioner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把「用户可见的技能」变成他工作区里的文件（H-06a / DR-41）。
 *
 * <p>它只做一件事：**同步**。目标状态是「清单里那些技能的最新已发布版本」，现状是工作区里的
 * {@code skills/} 目录，差量自己算。写完之后，技能的**发现与使用**全是框架的事——
 * 框架自带的 {@code WorkspaceSkillRepository} 会扫 {@code skills/<编码>/SKILL.md}，
 * 把它列进系统提示词，并提供按需读取内容的工具。平台不碰这一段。
 *
 * <p><b>每一步的代价</b>（多副本下每轮都会走一遍，所以每一步都要算清楚）：
 * <ol>
 *   <li>读一次工作区里的索引文件（一次共享存储读）；</li>
 *   <li>一次批量 SQL 问「这些技能现在算数的是哪一版」；</li>
 *   <li>**只有版本变了的技能**才去对象存储取包、解包、写文件。</li>
 * </ol>
 * 什么都没变的时候，代价就是「一次读 + 一次查」，不进对象存储——这是把「哪一版算数」与
 * 「包的字节」拆成两步查询的原因（见 {@link SkillPackageSource}）。
 *
 * <p><b>为什么索引放在工作区里而不是放数据库</b>：它描述的是「这个用户的工作区里现在有什么」，
 * 与工作区同生共死。放数据库就得再管一张表、再管一致性；放工作区里，多副本任意一台写完，
 * 别的实例读到的就是同一份（工作区本身是共享的）。
 *
 * <p><b>两条已知边界</b>：① 单文件超过 {@code PackageReader} 的 256KB 上限时不会进包内文件表，
 * 因此不会下发（现在没有这种技能；真有了要连着那个上限一起改）；② 这里只管**放**文件，
 * 脚本跑不跑由沙箱决定（H-06b：沙箱开着时，运行时会把这些技能投影进容器，脚本在容器里跑）。
 */
public final class WorkspaceSkillProvisioner implements SkillProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSkillProvisioner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 技能都放在工作区的这个目录下——框架默认的工作区技能仓库扫的就是它（{@code WorkspaceSkillRepository} 的 {@code "skills"}）。 */
    static final String SKILLS_DIR = "skills";

    /** 索引文件名：点号开头、而且不是一个目录，所以框架「扫每个技能目录里的 SKILL.md」时看不见它。 */
    static final String INDEX_PATH = SKILLS_DIR + "/.platform-skills.json";

    /** 框架认识的技能入口文件名。 */
    static final String SKILL_FILE = "SKILL.md";

    private final SkillPackageSource source;

    /**
     * 这台实例有没有「执行面」——也就是沙箱开着没开（H-05 / H-06b）。
     *
     * <p>它只影响**写给模型看的那句话**：技能正文里要不要说"脚本现在跑不了"。
     * 说错了代价很直接——沙箱开着却说跑不了，模型不敢用技能；沙箱关着却没说，模型会承诺"我帮你跑一下"。
     * 技能内容本身、写文件的方式，两种情况下完全一样。
     */
    private final boolean scriptExecutionAvailable;

    /** 不带执行面（等同"沙箱没开"）：技能只下发内容，脚本不跑。 */
    public WorkspaceSkillProvisioner(SkillPackageSource source) {
        this(source, false);
    }

    public WorkspaceSkillProvisioner(SkillPackageSource source, boolean scriptExecutionAvailable) {
        this.source = source;
        this.scriptExecutionAvailable = scriptExecutionAvailable;
    }

    @Override
    public void provision(AbstractFilesystem workspace, RuntimeContext ctx, List<String> visibleSkills) {
        if (workspace == null || ctx == null) {
            return;
        }
        try {
            sync(workspace, ctx, visibleSkills);
        } catch (RuntimeException e) {
            // 技能是增强，不是主链路：下发失败就让这一轮照常跑（工作区里留着上一次下发的那份）。
            // 注意顺序——下面所有会改工作区的动作都排在「清单与版本都算出来之后」，
            // 所以这里 catch 到的失败不会留下「删了一半」的中间状态。
            log.warn("技能下发失败（这一轮按工作区里现有的技能继续）：userId={} 原因={}", ctx.getUserId(), e.toString());
        }
    }

    private void sync(AbstractFilesystem workspace, RuntimeContext ctx, List<String> visibleSkills) {
        List<String> wanted = visibleSkills == null ? List.of() : List.copyOf(new LinkedHashSet<>(visibleSkills));
        Map<String, SkillPackageSource.Published> published = source.published(wanted);
        Index previous = readIndex(workspace, ctx);

        Index next = new Index();
        int written = 0;
        for (String code : wanted) {
            SkillPackageSource.Published version = published.get(code);
            if (version == null) {
                // 清单里有、但没有已发布的内容（上传了没发布 / 已停用）：这次不下发。
                log.debug("技能 {} 没有已发布的版本，本次不下发", code);
                continue;
            }
            Entry known = previous.skills.get(code);
            if (known != null && known.version.equals(version.version())) {
                next.skills.put(code, known);   // 没变：原样保留，连包都不用取
                continue;
            }
            PackageContent content = source.load(version).orElse(null);
            if (content == null) {
                if (known != null) {
                    next.skills.put(code, known);   // 取不到新的，就把旧的那份留着，别让技能凭空消失
                }
                continue;
            }
            Entry entry = writeSkill(workspace, ctx, code, version, content, known);
            if (entry != null) {
                next.skills.put(code, entry);
                written++;
            } else if (known != null) {
                next.skills.put(code, known);
            }
        }

        // 撤销：上一次在、这一次不在（授权被收走 / 技能被停用）→ 把它写过的文件删干净。
        int removed = 0;
        for (Map.Entry<String, Entry> stale : previous.skills.entrySet()) {
            if (!next.skills.containsKey(stale.getKey())) {
                deleteFiles(workspace, ctx, stale.getKey(), stale.getValue());
                removed++;
            }
        }

        if (written > 0 || removed > 0) {
            writeIndex(workspace, ctx, next);
            log.info("技能已同步到工作区：下发 {} 个、撤销 {} 个、当前 {} 个（userId={}）",
                    written, removed, next.skills.size(), ctx.getUserId());
        }
    }

    /**
     * 写一个技能：{@code SKILL.md} + 包内其它文件；返回这次实际写下的文件清单（撤销时要照它删）。
     *
     * @param previous 上一版写下的那份（第一次下发是 {@code null}）：用来清掉「这一版已经没有、上一版还有」的旧文件
     */
    private Entry writeSkill(
            AbstractFilesystem workspace,
            RuntimeContext ctx,
            String code,
            SkillPackageSource.Published version,
            PackageContent content,
            Entry previous) {
        String root = SKILLS_DIR + "/" + code;
        List<String> files = new ArrayList<>();
        Set<String> intended = new LinkedHashSet<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : content.files().entrySet()) {
            // 包作者自己写了 SKILL.md 就用他的：那是他写给模型看的说明，比我们从 manifest 拼的准。
            if (SKILL_FILE.equals(file.getKey())) {
                continue;
            }
            String path = root + "/" + file.getKey();
            resources.put(path, file.getValue());
            intended.add(path);
        }
        String skillMarkdown = content.has(SKILL_FILE)
                ? content.text(SKILL_FILE)
                : generateSkillMarkdown(code, content.manifest(), content.files().keySet(), scriptExecutionAvailable);
        String skillPath = root + "/" + SKILL_FILE;
        intended.add(skillPath);
        String failure = writeText(workspace, ctx, skillPath, skillMarkdown);
        if (failure != null) {
            log.warn("技能说明写不进工作区：skill={} 原因={}", code, failure);
            return null;
        }
        files.add(skillPath);
        if (!resources.isEmpty()) {
            List<Map.Entry<String, byte[]>> entries = new ArrayList<>(resources.entrySet());
            // 走 uploadFiles 而不是 write：write 只收字符串，脚本 / 模板里可能是二进制。
            for (var response : workspace.uploadFiles(ctx, entries)) {
                if (response.isSuccess()) {
                    files.add(response.path());
                } else {
                    log.warn("技能资源写不进工作区：skill={} 原因={}", code, response.error());
                }
            }
        }
        // 换版之后，上一版有、这一版没有的文件要清掉（详见方法注释）。
        deleteSupersededFiles(workspace, ctx, code, previous, intended);
        return new Entry(version.version(), List.copyOf(files));
    }

    /**
     * 清掉「上一版有、这一版已经没有」的文件（作者删掉某个脚本之类）。
     *
     * <p>为什么必须管：工作区里的文件是**模型看得见**的。旧版留下的脚本不清，它就还躺在那儿，
     * 等于「版本换了、旧文件没换」；而新索引里已经不记它，下一次撤销时谁都找不到它。
     *
     * <p>判断用「这一版**打算**写哪些」而不是「实际写成功哪些」：某个文件这次没写成功时，
     * 上一版那份要留着——那是模型唯一的副本，不能顺手删掉。
     */
    private static void deleteSupersededFiles(
            AbstractFilesystem workspace, RuntimeContext ctx, String code, Entry previous, Set<String> intended) {
        if (previous == null) {
            return;
        }
        for (String path : previous.files()) {
            if (!intended.contains(path)) {
                WriteResult deleted = workspace.delete(ctx, path);
                if (!deleted.isSuccess()) {
                    log.debug("上一版留下的文件没删掉（不影响这一版是否正确）：skill={} path={}", code, path);
                }
            }
        }
    }

    /**
     * 把一段文本写进工作区，**已存在就换成新的**。
     *
     * <p>为什么要绕这一下：框架的 {@code write} 是「不覆盖」语义——目标已存在时直接拒绝
     * （原话是 {@code Cannot write to ... because it already exists}），本意是不让模型悄悄盖掉别的东西。
     * 而技能下发恰恰是「把上一版换成这一版」：第一轮写下去之后，每次升版本、每次改索引都会撞上它。
     * 所以已存在时走框架的 {@code edit}（拿读到的内容整段替换，内部带版本校验与重试），不存在才 {@code write}。
     *
     * @return 失败原因；成功返回 {@code null}
     */
    private static String writeText(AbstractFilesystem workspace, RuntimeContext ctx, String path, String content) {
        ReadResult current = workspace.read(ctx, path, 0, 0);
        if (!current.isSuccess() || current.fileData() == null || current.fileData().content() == null) {
            WriteResult written = workspace.write(ctx, path, content);
            return written.isSuccess() ? null : written.error();
        }
        EditResult edited = workspace.edit(ctx, path, current.fileData().content(), content, false);
        return edited.isSuccess() ? null : edited.error();
    }
    private void deleteFiles(AbstractFilesystem workspace, RuntimeContext ctx, String code, Entry entry) {
        for (String path : entry.files()) {
            WriteResult deleted = workspace.delete(ctx, path);
            if (!deleted.isSuccess()) {
                // 删不掉不是致命问题：框架只认 SKILL.md，说明文件删掉了技能就不可见了。
                log.debug("技能文件删除未成功（不影响这个技能是否可见）：skill={} path={}", code, path);
            }
        }
    }

    /**
     * 由平台 manifest 生成 {@code SKILL.md}：模型看到的「这个技能是什么、怎么用、能拿到什么」。
     *
     * <p>格式用框架自己的 {@link MarkdownSkillParser#generate} 生成，不手写 YAML 头——
     * 头部格式是框架的解析契约（它怎么读，我们就怎么生成），自己拼迟早会有一次对不上。
     */
    private static String generateSkillMarkdown(
            String code, SkillManifest manifest, Set<String> resourcePaths, boolean scriptExecutionAvailable) {
        String name = manifest.name() == null || manifest.name().isBlank() ? code : manifest.name();
        StringBuilder body = new StringBuilder();
        body.append("# ").append(name).append("\n\n");
        if (manifest.description() != null && !manifest.description().isBlank()) {
            body.append(manifest.description()).append("\n");
        }
        if (!manifest.params().isEmpty()) {
            body.append("\n## 参数\n");
            manifest.params().forEach((param, spec) -> body.append("- ")
                    .append(param)
                    .append("：")
                    .append(paramDescription(spec))
                    .append("\n"));
        }
        if (!manifest.boundRoutes().isEmpty()) {
            body.append("\n## 绑定的数据接口\n");
            for (String route : manifest.boundRoutes()) {
                body.append("- ").append(route).append("\n");
            }
        }
        List<String> otherFiles = resourcePaths.stream().sorted().toList();
        if (!otherFiles.isEmpty()) {
            body.append("\n## 附带的文件\n");
            for (String path : otherFiles) {
                body.append("- ").append(path).append("\n");
            }
        }
        if (manifest.script() != null && manifest.script().path() != null) {
            // 如实写出来这台实例到底能不能跑脚本（沙箱开着的实例会把它投影进容器，见 H-06b）。
            // 不写这一句，模型要么照着跑不了的脚本给用户画饼，要么放着能跑的脚本不敢用。
            body.append("\n> 本技能声明的脚本是 ").append(manifest.script().path())
                    .append(scriptExecutionAvailable
                            ? "，配套文件已随技能放在工作区，并在执行环境（容器）的同一路径下可用，可以直接执行。\n"
                            : "，配套文件已随技能放在工作区；当前运行时不执行脚本，需要执行时如实说明。\n");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        if (manifest.description() != null && !manifest.description().isBlank()) {
            metadata.put("description", manifest.description());
        }
        metadata.put("platform-skill-code", code);
        if (manifest.version() != null) {
            metadata.put("platform-version", manifest.version());
        }
        return MarkdownSkillParser.generate(metadata, body.toString());
    }

    private static String paramDescription(Object spec) {
        if (spec instanceof Map<?, ?> map && map.get("description") != null) {
            return String.valueOf(map.get("description"));
        }
        return spec == null ? "" : String.valueOf(spec);
    }

    /** 读索引：没有（第一次下发）或读坏了，都按「现在是空的」处理——它只是个加速用的账本。 */
    private Index readIndex(AbstractFilesystem workspace, RuntimeContext ctx) {
        ReadResult read = workspace.read(ctx, INDEX_PATH, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || read.fileData().content() == null) {
            return new Index();
        }
        if (read.fileData().encoding() != null && !"utf-8".equalsIgnoreCase(read.fileData().encoding())) {
            // 索引是我们自己写的纯文本；读出别的编码说明这个键被别的东西占了，按空的处理。
            return new Index();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(read.fileData().content(), MAP_TYPE);
            Index index = new Index();
            Object skills = raw.get("skills");
            if (skills instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> item : map.entrySet()) {
                    if (!(item.getValue() instanceof Map<?, ?> entry)) {
                        continue;
                    }
                    String version = entry.get("version") == null ? null : String.valueOf(entry.get("version"));
                    List<String> files = new ArrayList<>();
                    if (entry.get("files") instanceof List<?> list) {
                        for (Object file : list) {
                            files.add(String.valueOf(file));
                        }
                    }
                    index.skills.put(String.valueOf(item.getKey()), new Entry(version, List.copyOf(files)));
                }
            }
            return index;
        } catch (Exception e) {
            log.warn("技能索引读不出来，按空的处理（下一轮会重新写一遍）：原因={}", e.toString());
            return new Index();
        }
    }

    private void writeIndex(AbstractFilesystem workspace, RuntimeContext ctx, Index index) {
        Map<String, Object> skills = new LinkedHashMap<>();
        index.skills.forEach((code, entry) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", entry.version);
            item.put("files", entry.files);
            skills.put(code, item);
        });
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("note", "由 agent-service 下发技能时维护；手改会被下一轮覆盖");
        raw.put("skills", skills);
        try {
            String json = MAPPER.writeValueAsString(raw);
            String failure = writeText(workspace, ctx, INDEX_PATH, json);
            if (failure != null) {
                log.warn("技能索引写不进工作区（下一轮会再试）：原因={}", failure);
            }
        } catch (Exception e) {
            log.warn("技能索引序列化失败（下一轮会再试）：原因={}", e.toString());
        }
    }

    /** 工作区里「这个用户现在有哪些技能、各是哪一版、各写了哪些文件」。 */
    private static final class Index {
        private final Map<String, Entry> skills = new LinkedHashMap<>();
    }

    private record Entry(String version, List<String> files) {}

}
