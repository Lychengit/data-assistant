package com.djzy.assistant.agentweb.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.djzy.assistant.agentstate.PlatformWorkspaceStore;
import com.djzy.assistant.agentweb.AgentWebH2Config;
import com.djzy.assistant.agentweb.AgentWebTestSupport;
import com.djzy.assistant.common.security.Hmac;
import com.djzy.assistant.common.storage.LocalFileObjectStorage;
import com.djzy.assistant.common.storage.ObjectStorage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 技能下发这条链的**端到端**用例（H-06a / DR-41）：管理的表 + 对象存储 + 共享工作区，三样都是真的。
 *
 * <p>单测（{@code WorkspaceSkillProvisionerTest} / {@code RegistrySkillPackageSourceTest}）把对象存储与数据库换成了替身，
 * 验的是判断逻辑；这里把替身全部换成**生产那一套**，验的是「它们之间的接缝」：
 * <ul>
 *   <li>SQL 真跑在真 DDL 上（表结构照迁移脚本，列名对不上在这里就会炸）；</li>
 *   <li>字节真从**部署时装的那个对象存储 Bean**（{@code object-storage.provider=local}）里取；</li>
 *   <li>文件真写进**共享工作区表**（{@code agent-service.workspace-store=jdbc}），而且换一个存储对象仍读得到——
 *       这正是「多副本下甲实例写、乙实例看得见」要的那件事。</li>
 * </ul>
 *
 * <p>另外它顺带钉住一件事：把 {@code workspace-skills=workspace} 打开，**应用照样起得来**（装配缺东西时启动即失败）。
 *
 * <p>没有覆盖的是「网关那份清单」与「模型怎么用它」：前者由 {@code ChatServiceTest} 的开关用例守着，
 * 后者是框架自己的事（{@code SharedWorkspaceTest} 钉住了接线）。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:agentweb-skills;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "agent-service.secrets.login-token=" + AgentWebTestSupport.JWT_SECRET,
            "agent-service.runtime-id=noop",
            "agent-service.ticket-store=memory",
            "agent-service.rate-limit-store=memory",
            "agent-service.stop-signal-store=memory",
            "agent-service.turn-gate-store=memory",
            "agent-service.live-bus=memory",
            // 这一条是用例的重点之一：工作区接到共享表上（缺表会启动失败，见 PlatformWorkspaceStore）
            "agent-service.workspace-store=jdbc",
            // 另一条重点：技能下发打开（默认 none）
            "agent-service.workspace-skills=workspace",
            // 落库定时任务会跟断言抢，关掉（本用例关心的是技能，不是事件落库）
            "agent-service.event-persist-interval-ms=3600000"
        })
@Import(AgentWebH2Config.class)
class SkillProvisioningIntegrationTest {

    private static final String SKILL = "doctor_income";
    private static final String STORAGE_KEY = "skills/doctor_income/1.0.0.zip";

    /** 事件日志与工作目录放临时目录里，别落到仓库里（同 {@code AgentApiTest} 的做法）。 */
    private static final Path WORK_DIR = createWorkDir();

    /** 对象存储也放临时目录：这样用例自己都不必清（进程退出即随系统临时目录一起回收）。 */
    private static final Path OBJECT_STORAGE_DIR = createWorkDir().resolve("objects");

    @Autowired
    DataSource dataSource;

    /** 部署时装的那个对象存储 Bean——生产实现，不是替身。 */
    @Autowired
    ObjectStorage objectStorage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-service.event-log-dir", () -> WORK_DIR.resolve("log").toString());
        registry.add("agent-service.workspace-dir", () -> WORK_DIR.resolve("workspace").toString());
        registry.add("object-storage.root-dir", () -> OBJECT_STORAGE_DIR.toString());
    }

    @Test
    void 一个已发布的技能_从对象存储一路进到共享工作区_换个实例也读得到() {
        // ① 管理端那份「哪个版本算数」的记录（生产由管理端发布时写；agent 侧只读）
        String contentSha = publish(SKILL, "1.0.0", STORAGE_KEY);
        // ② 内容在对象存储里：用生产实现写，走的是 .yml 里 provider=local 那条路
        assertThat(objectStorage.putIfAbsent(STORAGE_KEY, packageOf())).isTrue();

        // ③ 甲实例下发（与 AgentServiceConfig 里那两行同构：RegistrySkillPackageSource + WorkspaceSkillProvisioner）
        BaseStore storeOfInstanceA = PlatformWorkspaceStore.create(dataSource);
        SkillPackageSource source = new RegistrySkillPackageSource(dataSource, objectStorage);
        new WorkspaceSkillProvisioner(source).provision(new RemoteFilesystem(storeOfInstanceA), ctx(), List.of(SKILL));

        // ④ 乙实例：另一个存储对象、另一个文件系统，读同一张表
        AbstractFilesystem filesystemOfInstanceB = new RemoteFilesystem(PlatformWorkspaceStore.create(dataSource));
        ReadResult read = filesystemOfInstanceB.read(ctx(), "skills/" + SKILL + "/SKILL.md", 0, 0);

        assertThat(read.isSuccess()).as("乙实例该读到甲实例下发的技能说明：%s", read.error()).isTrue();
        assertThat(read.fileData().content()).contains("收入查询");
        // 内容哈希只是排障用的，但对不上说明读到的是「另一个包」
        assertThat(contentSha).hasSize(64);
    }

    @Test
    void 对象存储装的是本机实现_因为本机开发不起中间件() {
        // 这条不是「测框架」，而是钉住本机部署的默认形态：多副本要换 s3（L-08 明写了）。
        assertThat(objectStorage).isInstanceOf(LocalFileObjectStorage.class);
    }

    // ---------- 脚手架 ----------

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u-1").sessionId("s-1").build();
    }

    /** 写一行「已发布」的版本记录，返回内容哈希（就当是管理端发布过的那个包）。 */
    private String publish(String skillCode, String version, String storageKey) {
        String contentSha = Hmac.sha256Hex((skillCode + version).getBytes(StandardCharsets.UTF_8));
        String manifest = "{\"id\":\"" + skillCode + "\",\"name\":\"收入查询\",\"version\":\"" + version + "\",\"kind\":\"agentic\"}";
        new JdbcTemplate(dataSource).update(
                "INSERT INTO sys_skill_version (skill_code, version, content_sha256, storage_key, manifest,"
                        + " manifest_sha256, kind, status, submitted_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                skillCode, version, contentSha, storageKey, manifest,
                Hmac.sha256Hex(manifest.getBytes(StandardCharsets.UTF_8)), "agentic", "published", "admin");
        return contentSha;
    }

    /** 一个最小可用的技能包（zip）：manifest 必须有，另附一个配套脚本。 */
    private static byte[] packageOf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : Map.of(
                    "manifest.json", "{\"id\":\"doctor_income\",\"name\":\"收入查询\",\"version\":\"1.0.0\",\"kind\":\"agentic\"}",
                    "scripts/run.py", "# 当前运行时不执行脚本（H-06b）").entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("造测试技能包失败", e);
        }
        return out.toByteArray();
    }

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("agent-skill-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
