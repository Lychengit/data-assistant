package com.djzy.assistant.agentweb.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.skill.PackageContent;
import com.djzy.assistant.common.skill.SkillManifest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 技能下发（H-06a / DR-41）：把「用户可见的技能」搬进工作区，并保证**多副本下每一轮都划算**。
 *
 * <p>这一层要钉住的不是「文件写没写进去」（那是顺带的），而是三条**多副本下每轮都要付的账**：
 * ① 版本没变就不重写、也不去对象存储取包；② 授权被收走时工作区里的旧文件要清干净；
 * ③ 任何一步失败都不能把这一轮对话带崩（技能是增强，不是主链路）。
 *
 * <p>文件系统用框架自己的 {@code RemoteFilesystem} + 内存 store：它和生产的共享工作区是同一份实现，
 * 只是把「落哪儿」换成了内存，所以这里验到的读写语义就是生产那一套（命名空间隔离另有 {@code SharedWorkspaceTest} 守着）。
 * 技能内容来自一个假的 {@link SkillPackageSource}，这样数据库与对象存储都不会进到这个用例里。
 */
class WorkspaceSkillProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String INDEX = "skills/.platform-skills.json";
    private static final String CODE = "doctor_income";

    private final AbstractFilesystem workspace = new RemoteFilesystem(new InMemoryStore());

    @Test
    void 第一次下发_技能说明与配套文件都写进工作区() {
        FakeSource source = new FakeSource()
                .publish(CODE, "1.0.0", Map.of("scripts/run.py", "print(1)"));

        new WorkspaceSkillProvisioner(source).provision(workspace, ctx(), List.of(CODE));

        // 包里有 manifest 但没有 SKILL.md：说明文件由平台按 manifest 生成（模型看到的就是它）。
        String skill = read("skills/" + CODE + "/SKILL.md");
        assertTrue(skill.contains("# 收入查询"), () -> "生成的技能说明里该有名字：" + skill);
        assertEquals("print(1)", read("skills/" + CODE + "/scripts/run.py"), "配套文件要原样放进去（脚本执行是 H-06b）");

        Map<String, Object> index = index();
        assertEquals("1.0.0", versionOf(index, CODE));
        assertEquals(
                new HashSet<>(List.of("skills/" + CODE + "/SKILL.md", "skills/" + CODE + "/scripts/run.py")),
                new HashSet<>(filesOf(index, CODE)),
                "索引要记下这次写了哪些文件——撤销时就是照它删");
    }

    @Test
    void 版本没变_不重写文件也不去取包() {
        FakeSource source = new FakeSource().publish(CODE, "1.0.0", Map.of());
        WorkspaceSkillProvisioner provisioner = new WorkspaceSkillProvisioner(source);

        provisioner.provision(workspace, ctx(), List.of(CODE));
        assertEquals(1, source.loadCalls);

        provisioner.provision(workspace, ctx(), List.of(CODE));

        assertEquals(1, source.loadCalls, "第二轮版本没变，就不该再去对象存储取一次包");
    }

    @Test
    void 清单里没有的技能_文件被删干净() {
        FakeSource source = new FakeSource().publish(CODE, "1.0.0", Map.of("scripts/run.py", "print(1)"));
        WorkspaceSkillProvisioner provisioner = new WorkspaceSkillProvisioner(source);
        provisioner.provision(workspace, ctx(), List.of(CODE));

        // 授权被收走：这一轮网关给的清单里已经没有它了。
        provisioner.provision(workspace, ctx(), List.of());

        assertFalse(workspace.exists(ctx(), "skills/" + CODE + "/SKILL.md"), "技能说明没删掉，模型还会看见它");
        assertFalse(workspace.exists(ctx(), "skills/" + CODE + "/scripts/run.py"), "配套文件也要一起清掉");
        assertNull(versionOf(index(), CODE), "索引里也不该再留着这个技能");
    }

    @Test
    void 包里自带说明文件时_用作者写的那份() {
        FakeSource source = new FakeSource().publish(CODE, "1.0.0", Map.of("SKILL.md", "作者写的说明"));

        new WorkspaceSkillProvisioner(source).provision(workspace, ctx(), List.of(CODE));

        assertEquals("作者写的说明", read("skills/" + CODE + "/SKILL.md"), "作者写给模型看的说明比平台生成的准");
        // 而且它不该被当成「配套文件」再写第二遍。
        assertEquals(new HashSet<>(List.of("skills/" + CODE + "/SKILL.md")), new HashSet<>(filesOf(index(), CODE)));
    }

    @Test
    void 版本变了_工作区里的文件换成新的一版_旧文件清掉() {
        FakeSource source = new FakeSource().publish(CODE, "1.0.0", Map.of("scripts/old.py", "旧脚本"));
        WorkspaceSkillProvisioner provisioner = new WorkspaceSkillProvisioner(source);
        provisioner.provision(workspace, ctx(), List.of(CODE));

        // 新版：脚本换了名字，旧的那个这一版没有了。
        source.publish(CODE, "2.0.0", Map.of("scripts/new.py", "新脚本"));
        provisioner.provision(workspace, ctx(), List.of(CODE));

        // 这一条同时钉住「覆盖写」：框架的 write 拒绝写已存在的路径，
        // 改用 edit 之前，索引与 SKILL.md 都停在第一版，版本号根本推不动。
        assertEquals("2.0.0", versionOf(index(), CODE), "索引要能改成新版本号");
        assertEquals("新脚本", read("skills/" + CODE + "/scripts/new.py"));
        assertFalse(workspace.exists(ctx(), "skills/" + CODE + "/scripts/old.py"), "上一版的旧文件要清掉");
        assertEquals(2, source.loadCalls);
    }
    @Test
    void 取不到新版本时_保留旧的那份不让技能凭空消失() {
        FakeSource source = new FakeSource().publish(CODE, "1.0.0", Map.of());
        WorkspaceSkillProvisioner provisioner = new WorkspaceSkillProvisioner(source);
        provisioner.provision(workspace, ctx(), List.of(CODE));
        String before = read("skills/" + CODE + "/SKILL.md");

        // 管理端发了新版，但对象存储里这份取不到（被删了 / 存储抖了一下）。
        source.publish(CODE, "2.0.0", Map.of()).loadFails();
        provisioner.provision(workspace, ctx(), List.of(CODE));

        assertEquals(before, read("skills/" + CODE + "/SKILL.md"), "取不到新的就该留着旧的那份");
        assertEquals("1.0.0", versionOf(index(), CODE), "索引仍记着仍然生效的那一版");
    }

    @Test
    void 清单里有但没发布过_这次不下发也不报错() {
        FakeSource source = new FakeSource();

        new WorkspaceSkillProvisioner(source).provision(workspace, ctx(), List.of("ghost_skill"));

        assertFalse(workspace.exists(ctx(), "skills/ghost_skill/SKILL.md"), "没有可用内容就不该凭空造一个技能出来");
    }

    @Test
    void 来源出错时_只记日志不把这一轮带崩() {
        FakeSource broken = new FakeSource().explode();

        assertDoesNotThrow(() -> new WorkspaceSkillProvisioner(broken).provision(workspace, ctx(), List.of(CODE)));
    }

    @Test
    void 空清单_什么都不做() {
        FakeSource source = new FakeSource().publish(CODE, "1.0.0", Map.of());

        new WorkspaceSkillProvisioner(source).provision(workspace, ctx(), null);

        assertFalse(workspace.exists(ctx(), INDEX), "清单为空时连索引都不必写");
    }

    /**
     * 技能说明里那句「脚本能不能跑」要跟着沙箱走（H-06b）。
     *
     * <p>说反了两头都难看：沙箱开着却说跑不了，模型不敢用技能；沙箱关着却没说，模型会给用户
     * 承诺一句「我帮你跑一下」，然后什么也跑不出来。技能内容本身在两种情况下完全一样。
     */
    @Test
    void 脚本那句话跟着沙箱走_技能内容本身不变() {
        FakeSource source = new FakeSource()
                .publishWithScript(CODE, "1.0.0", Map.of("scripts/run.py", "print(1)"), "scripts/run.py");

        // 两份互不影响的用户工作区：版本没变时下发器本来就不重写（那是它省成本的地方），
        // 所以要看另一种档位，得换一份空工作区，而不是在同一份上再下发一次。
        AbstractFilesystem withSandboxWorkspace = workspace;
        AbstractFilesystem withoutSandboxWorkspace = new RemoteFilesystem(new InMemoryStore());

        new WorkspaceSkillProvisioner(source, true).provision(withSandboxWorkspace, ctx(), List.of(CODE));
        String withSandbox = read(withSandboxWorkspace, "skills/" + CODE + "/SKILL.md");
        assertTrue(withSandbox.contains("可以直接执行"), () -> "沙箱开着时要说脚本跑得动：" + withSandbox);

        // 同一份技能，换一个「没开沙箱」的下发器：文件与内容都一样，只有那句话不同。
        new WorkspaceSkillProvisioner(source, false).provision(withoutSandboxWorkspace, ctx(), List.of(CODE));
        String withoutSandbox = read(withoutSandboxWorkspace, "skills/" + CODE + "/SKILL.md");
        assertTrue(withoutSandbox.contains("当前运行时不执行脚本"), () -> "没开沙箱时要说清楚脚本不跑：" + withoutSandbox);
        assertEquals(
                withoutScriptNote(withSandbox),
                withoutScriptNote(withoutSandbox),
                "除了「脚本能不能跑」那一句，两种情况下写下的技能说明必须逐字相同");
    }

    // ---------- 下面都是脚手架：假来源、读工作区的小工具 ----------

    /** 去掉「脚本怎么跑」那一句之后剩下的内容：用来确认两种沙箱档位只差这一句。 */
    private static String withoutScriptNote(String markdown) {
        return markdown.lines()
                .filter(line -> !line.startsWith("> 本技能声明的脚本是"))
                .reduce("", (kept, line) -> kept + line + "\n");
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u-1").sessionId("s-1").build();
    }

    private String read(String path) {
        return read(workspace, path);
    }

    private String read(AbstractFilesystem from, String path) {
        ReadResult result = from.read(ctx(), path, 0, 0);
        assertTrue(result.isSuccess(), () -> path + " 该读得到，实际失败：" + result.error());
        return result.fileData().content();
    }

    private Map<String, Object> index() {
        try {
            return MAPPER.readValue(read(INDEX), MAP_TYPE);
        } catch (Exception e) {
            throw new AssertionError("技能索引不是合法 JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String versionOf(Map<String, Object> index, String code) {
        Map<String, Object> entry = entryOf(index, code);
        return entry == null ? null : (String) entry.get("version");
    }

    @SuppressWarnings("unchecked")
    private static List<String> filesOf(Map<String, Object> index, String code) {
        Map<String, Object> entry = entryOf(index, code);
        return entry == null ? List.of() : (List<String>) entry.get("files");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryOf(Map<String, Object> index, String code) {
        Object skills = index.get("skills");
        return skills instanceof Map<?, ?> map ? (Map<String, Object>) map.get(code) : null;
    }

    /** 假的技能包来源：省掉数据库与对象存储，只留下「哪一版算数 + 包内容」这两件事。 */
    private static final class FakeSource implements SkillPackageSource {

        private final Map<String, Published> published = new LinkedHashMap<>();
        private final Map<String, PackageContent> packages = new LinkedHashMap<>();
        private int loadCalls;
        private boolean loadFails;
        private boolean explode;

        FakeSource publish(String code, String version, Map<String, String> files) {
            return publish(code, version, files, null);
        }

        /** 声明了脚本的包：技能说明里会多出「脚本怎么跑」那一句（H-06b 按沙箱开关分岔）。 */
        FakeSource publishWithScript(String code, String version, Map<String, String> files, String scriptPath) {
            return publish(code, version, files, scriptPath);
        }

        private FakeSource publish(String code, String version, Map<String, String> files, String scriptPath) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("id", code);
            manifest.put("name", "收入查询");
            manifest.put("version", version);
            manifest.put("kind", "agentic");
            manifest.put("description", "查医生收入");
            if (scriptPath != null) {
                manifest.put("script", Map.of("path", scriptPath));
            }
            Published ref = new Published(code, version, "sha-" + code + "-" + version, storageKey(code, version));
            published.put(code, ref);
            packages.put(ref.storageKey(), content(manifest, files));
            return this;
        }

        FakeSource loadFails() {
            this.loadFails = true;
            return this;
        }

        FakeSource explode() {
            this.explode = true;
            return this;
        }

        @Override
        public Map<String, Published> published(List<String> skillCodes) {
            if (explode) {
                throw new IllegalStateException("模拟一次查库失败");
            }
            Map<String, Published> found = new LinkedHashMap<>();
            for (String code : skillCodes) {
                Published ref = published.get(code);
                if (ref != null) {
                    found.put(code, ref);
                }
            }
            return Map.copyOf(found);
        }

        @Override
        public Optional<PackageContent> load(Published ref) {
            loadCalls++;
            return loadFails ? Optional.empty() : Optional.ofNullable(packages.get(ref.storageKey()));
        }

        private static String storageKey(String code, String version) {
            return "skills/" + code + "/" + version + ".zip";
        }
    }

    /** 造一份「已解开」的包内容；生产是 {@code PackageReader.read(zip)} 的产物，字段口径一致。 */
    private static PackageContent content(Map<String, Object> manifest, Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return new PackageContent(
                "sha-content",
                SkillManifest.from(manifest),
                "{}",
                "sha-manifest",
                Map.copyOf(bytes),
                List.copyOf(bytes.keySet()));
    }
}
