package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.djzy.assistant.management.ManagementTestSupport.H2DialectConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M3 技能包管理（§18.4.6 M3 / §18.5.2 / §19.2 / §20.7）：上传 → 自动检查 → 评审 → 发布 → 停用。
 *
 * <p>重点盯住四条口径：内容寻址（同内容不产生第二版）、自动检查 blocking 失败**自动驳回**、
 * 评审对象只能是**最新包**、发布/停用都**同事务**写 {@code config_audit}。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-skill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class SkillPackageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String SKILL = "doctor_income";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM skill_review");
        jdbc.update("DELETE FROM skill_check");
        jdbc.update("DELETE FROM sys_skill_version");
        jdbc.update("DELETE FROM skill_api");
        jdbc.update("DELETE FROM sys_skill");
        jdbc.update("DELETE FROM config_audit");
        jdbc.update("DELETE FROM sys_api WHERE http_path = '/doctor/export-job'");
        // 接口的启用状态也要复位：改绑用例会临时停用 /doctor/list 来验证「停用接口不许绑」，
        // 用例之间共用同一份 H2（DB_CLOSE_DELAY=-1），不复位就会串味成「后一个用例莫名 400」
        jdbc.update("UPDATE sys_api SET enabled = TRUE");
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanReachTheSkillAdminSurface() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/skill")));
        assertEquals(403, statusOf(get("/v1/admin/skill").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
        // 页面改绑也是 admin 独有：技能↔接口一旦能被人随手改，等于把接口调用的口子开给所有人
        assertEquals(
                403,
                statusOf(put("/v1/admin/skill/" + SKILL + "/apis")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boundRoutes\":[]}")));

        assertEquals(
                403,
                statusOf(multipart("/v1/admin/skill/upload")
                        .file(new MockMultipartFile(
                                "file", "skill.zip", "application/zip", packageOf(manifest("1.0.0", Map.of()))))
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));

        assertEquals(0L, count("sys_skill_version"));
        assertEquals(0L, count("config_audit"));
    }

    @Test
    void uploadRunsChecksAndParksTheVersionAtChecked() throws Exception {
        Map<String, Object> uploaded = upload(manifest("1.0.0", Map.of()));
        assertEquals(false, uploaded.get("duplicateContent"));
        assertEquals("checked", version(uploaded).get("status"));
        assertEquals(SKILL, version(uploaded).get("skillCode"));

        List<Map<String, Object>> checks = checks(uploaded);
        Map<String, Object> completeness = checkByCode(checks, "MANIFEST_REQUIRED_FIELDS");
        assertEquals(true, completeness.get("passed"));
        assertEquals(true, checkByCode(checks, "EXPORT_COLUMNS_WHITELISTED").get("passed"));
        // 静态提示项永远只是 warning，不冒充拦截（§18.12.2）
        assertEquals("warning", checkByCode(checks, "NETWORK_EGRESS").get("severity"));
        assertEquals("checked", jdbc.queryForObject("SELECT status FROM sys_skill_version", String.class));
    }

    @Test
    void sameContentUploadedTwiceIsTheSameObject() throws Exception {
        byte[] bytes = packageOf(manifest("1.0.0", Map.of()));
        Map<String, Object> first = upload(bytes);
        long versions = count("sys_skill_version");
        long checks = count("skill_check");

        Map<String, Object> second = upload(bytes);
        assertEquals(true, second.get("duplicateContent"));
        assertEquals(version(first).get("id"), version(second).get("id"));
        assertEquals(versions, count("sys_skill_version"));
        assertEquals(checks, count("skill_check"));
    }

    /**
     * 「连文件夹一起压缩」（Windows 右键压缩的默认产物）也要能传上来：包根自动认出，包内路径照旧是相对的。
     *
     * <p>2026-09-27 实测：这种包原本报 500「服务暂不可用」——而它恰恰是**最常见的打包方式**。
     */
    @Test
    void packageZippedTogetherWithItsFolderIsAccepted() throws Exception {
        Map<String, Object> body = minimalManifest();
        body.put("script", Map.of("path", "scripts/run.py", "data", "none", "timeoutSeconds", 60));
        byte[] wrapped = wrappedPackageOf("doctor_income-1.1.6", body, Map.of("scripts/run.py", "print(1)"));

        Map<String, Object> uploaded = upload(wrapped);

        assertEquals(false, uploaded.get("duplicateContent"));
        assertEquals("checked", version(uploaded).get("status"));
        // 剥掉那层目录之后 script.path 仍然相对包根：SCRIPT_PRESENT 查的就是它
        assertEquals(true, checkByCode(checks(uploaded), "SCRIPT_PRESENT").get("passed"));
    }

    /** 包里哪儿都没有 manifest.json → 400（不是 500），而且要说清「怎么改」。 */
    @Test
    void packageWithoutManifestIsRejectedAsBadRequest() throws Exception {
        byte[] wrapped = wrappedPackageOf("doctor_income-1.1.6", null, Map.of("scripts/run.py", "print(1)"));

        MvcResult result = mvc.perform(multipart("/v1/admin/skill/upload")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", wrapped))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        Map<String, Object> body = read(result);
        assertEquals("MANIFEST_MISSING", body.get("code"));
        assertTrue(String.valueOf(body.get("error")).contains("manifest.json"));
        assertEquals(0L, count("sys_skill_version"));
    }

    /**
     * 最小 manifest：只写 id / name / description 三项。
     *
     * <p>这是 2026-09-27 的降门槛口径——写 manifest 的人常常不是写代码的人，十项必填意味着十次
     * 「少写一个就被驳回」。版本按内容生成、类型按有没有 script 块推、绑定接口上传后在页面里配。
     */
    @Test
    void minimalManifestOnlyNeedsIdNameAndDescription() throws Exception {
        Map<String, Object> uploaded = upload(minimalManifest());

        assertEquals(false, uploaded.get("duplicateContent"));
        assertEquals("checked", version(uploaded).get("status"));
        assertEquals("agentic", version(uploaded).get("kind"));
        // 版本行必须有个版本号（没写就是自动生成的那个），不能是 null 撞 NOT NULL
        assertTrue(
                String.valueOf(version(uploaded).get("version")).startsWith("0.0-"),
                "自动版本应形如 0.0-<内容前 8 位>，实际：" + version(uploaded).get("version"));
        for (Map<String, Object> check : checks(uploaded)) {
            if ("blocking".equals(check.get("severity"))) {
                assertEquals(true, check.get("passed"), "最小 manifest 不该被任何 blocking 项拦住：" + check);
            }
        }
        // 没绑接口就没有接口权限，发布出来也是「看得到、调不动」——这正是页面「绑定接口」要补的那一步
        Map<String, Object> publishable = minimalManifest();
        publishable.put("description", "最小包也能直接发布");
        publish(publishable);
        assertEquals(List.of(), boundRoutesViaApi(SKILL));
        assertEquals(0L, count("skill_api"));
        assertEquals("active", jdbc.queryForObject(
                "SELECT status FROM sys_skill WHERE skill_code = '" + SKILL + "'", String.class));
    }

    @Test
    void scriptKindIsInferredFromTheScriptBlockAndItsPathIsStillRequired() throws Exception {
        Map<String, Object> scripted = minimalManifest();
        scripted.put("script", Map.of("path", "scripts/run.py", "data", "none", "timeoutSeconds", 30));
        Map<String, Object> uploaded = upload(packageOf(scripted, Map.of("scripts/run.py", "print(1)")));
        assertEquals("script", version(uploaded).get("kind"));
        assertEquals("checked", version(uploaded).get("status"));

        // 声明成脚本技能就不能只说「有脚本」：data / timeoutSeconds / 文件在不在，仍然逐条拦
        Map<String, Object> missingPolicy = minimalManifest();
        missingPolicy.put("description", "缺取数策略");
        missingPolicy.put("script", Map.of("path", "scripts/run.py"));
        Map<String, Object> rejected = upload(packageOf(missingPolicy, Map.of("scripts/run.py", "print(1)")));
        assertEquals("rejected", version(rejected).get("status"));
        assertEquals(false, checkByCode(checks(rejected), "SCRIPT_DATA_POLICY").get("passed"));
    }

    @Test
    void missingIdNameOrDescriptionIsStillRejected() throws Exception {
        Map<String, Object> noDescription = minimalManifest();
        noDescription.remove("description");
        Map<String, Object> uploaded = upload(noDescription);
        assertEquals("rejected", version(uploaded).get("status"));
        Map<String, Object> check = checkByCode(checks(uploaded), "MANIFEST_REQUIRED_FIELDS");
        assertEquals(false, check.get("passed"));
        assertTrue(String.valueOf(check.get("detail")).contains("description"));
    }

    @Test
    void republishingAPackageThatDoesNotDeclareBoundRoutesKeepsThePageBindings() throws Exception {
        publish(manifest("1.0.0", Map.of()));
        assertEquals(200, rebind(SKILL, List.of("interface-doctor POST /doctor/list")).getResponse().getStatus());

        // 新包没写 boundRoutes（不是写空数组）：绑定不归这个包管，页面配的那份必须原样留着。
        // 反例是「空集合 = 清空」——那样一次重新发布就会静默抹掉管理员勾出来的绑定。
        publish(manifestWithoutBindings("2.0.0"));
        assertEquals(List.of("interface-doctor POST /doctor/list"), boundRoutesViaApi(SKILL));
        assertEquals(1L, count("skill_api"));
    }

    @Test
    void explicitEmptyBoundRoutesClearsTheBindings() throws Exception {
        publish(manifest("1.0.0", Map.of()));
        assertEquals(1L, count("skill_api"));

        // 写了 [] 就是「这个包声明了零个接口」——与「没写」是两回事，这里要清空
        publish(manifest("2.0.0", Map.of("boundRoutes", List.of())));
        assertEquals(List.of(), boundRoutesViaApi(SKILL));
        assertEquals(0L, count("skill_api"));
    }

    @Test
    void blockingFailureIsAutoRejectedWithASystemReview() throws Exception {
        // blocking 项用「绑了没注册过的接口」触发（BOUND_APIS_REGISTERED）
        Map<String, Object> uploaded =
                upload(manifest("1.0.0", Map.of("boundRoutes", List.of("interface-doctor POST /doctor/missing"))));
        assertEquals("rejected", version(uploaded).get("status"));
        assertEquals(false, checkByCode(checks(uploaded), "BOUND_APIS_REGISTERED").get("passed"));

        Map<String, Object> review = jdbc.queryForMap("SELECT * FROM skill_review ORDER BY id DESC LIMIT 1");
        assertEquals("system", review.get("reviewer"));
        assertEquals("rejected", review.get("decision"));
        // 自动驳回是「说不清楚就不许上线」，不是「先挂起来」，因此不会出现在待评审列表里
        assertEquals(0, readList(mvc.perform(get("/v1/admin/skill/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn()).size());
    }

    @Test
    void exportColumnsOutsideTheBoundApiWhitelistAreBlocking() throws Exception {
        Map<String, Object> uploaded = upload(manifest("1.0.0", Map.of("exports", List.of("doctor_id", "salary"))));
        assertEquals("rejected", version(uploaded).get("status"));
        Map<String, Object> check = checkByCode(checks(uploaded), "EXPORT_COLUMNS_WHITELISTED");
        assertEquals(false, check.get("passed"));
        assertTrue(String.valueOf(check.get("detail")).contains("salary"));
    }

    @Test
    void approvePublishesTheVersionAndApprovesItsApiBindings() throws Exception {
        Map<String, Object> uploaded = upload(manifest("1.0.0", Map.of()));
        long versionId = ((Number) version(uploaded).get("id")).longValue();

        Map<String, Object> reviewed = read(mvc.perform(post("/v1/admin/skill/versions/" + versionId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"reason\":\"绑定接口最小、无外发\"}"))
                .andReturn());
        assertEquals("published", reviewed.get("status"));
        assertEquals(ManagementTestSupport.ADMIN, reviewed.get("publishedBy"));

        // 发布 = 版本状态 + sys_skill upsert + skill_api 审核，一个事务里一起落（§19.2 / ADR-19）
        Map<String, Object> skill = jdbc.queryForMap("SELECT * FROM sys_skill WHERE skill_code = '" + SKILL + "'");
        assertEquals("active", skill.get("status"));
        assertEquals("医生收入查询", skill.get("name"));
        Map<String, Object> binding = jdbc.queryForMap("SELECT * FROM skill_api ORDER BY api_id LIMIT 1");
        assertEquals(true, binding.get("approved"));
        assertEquals(ManagementTestSupport.ADMIN, binding.get("approved_by"));

        Map<String, Object> review = jdbc.queryForMap("SELECT * FROM skill_review ORDER BY id DESC LIMIT 1");
        assertEquals("approved", review.get("decision"));
        // 发布要留痕：before=checked / after=published，且记的是同一次变更（§20.7）
        Map<String, Object> audit = lastAudit();
        assertEquals(ManagementTestSupport.ADMIN, audit.get("who"));
        assertTrue(String.valueOf(audit.get("before")).contains("checked"));
        assertTrue(String.valueOf(audit.get("after")).contains("published"));
        assertEquals(0, readList(mvc.perform(get("/v1/admin/skill/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn()).size());
    }

    @Test
    void onlyTheLatestPackageCanBeReviewed() throws Exception {
        Map<String, Object> older = upload(manifest("1.0.0", Map.of("description", "第一版")));
        upload(manifest("2.0.0", Map.of("description", "第二版")));
        long olderId = ((Number) version(older).get("id")).longValue();

        MvcResult result = mvc.perform(post("/v1/admin/skill/versions/" + olderId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("checked", jdbc.queryForObject(
                "SELECT status FROM sys_skill_version WHERE id = " + olderId, String.class));
        assertEquals(0L, count("sys_skill"));
    }

    @Test
    void pageRebindingReplacesTheBoundApisAndIsAudited() throws Exception {
        publish(manifest("1.0.0", Map.of()));
        assertEquals(List.of("interface-doctor POST /doctor/performance"), boundRoutesViaApi(SKILL));

        // 整体替换：换成 /doctor/list 之后，发布时那条绑定不留残行（绑定是集合，不是增量）
        assertEquals(200, rebind(SKILL, List.of("interface-doctor POST /doctor/list")).getResponse().getStatus());
        assertEquals(List.of("interface-doctor POST /doctor/list"), boundRoutesViaApi(SKILL));
        assertEquals(1L, count("skill_api"));
        assertEquals(2L, jdbc.queryForObject("SELECT api_id FROM skill_api", Long.class));
        assertEquals(true, jdbc.queryForObject("SELECT approved FROM skill_api", Boolean.class));
        assertEquals(
                ManagementTestSupport.ADMIN,
                jdbc.queryForObject("SELECT approved_by FROM skill_api", String.class));

        // 改绑不是「改了但没记账」：before / after 两个集合都要在审计里
        Map<String, Object> audit = lastAudit();
        assertEquals("skill", audit.get("target"));
        assertEquals("skill_api:" + SKILL, audit.get("field"));
        assertTrue(String.valueOf(audit.get("before")).contains("/doctor/performance"));
        assertTrue(String.valueOf(audit.get("after")).contains("/doctor/list"));

        // 空集合 = 清空绑定：这是管理员能做的显式动作，不许被当成「保持不动」
        assertEquals(200, rebind(SKILL, List.of()).getResponse().getStatus());
        assertEquals(List.of(), boundRoutesViaApi(SKILL));
        assertEquals(0L, count("skill_api"));
    }

    @Test
    void pageRebindingTakesWriteApisButStillRefusesUnregisteredAndDisabledOnes() throws Exception {
        publish(manifest("1.0.0", Map.of()));
        jdbc.update("INSERT INTO sys_api (name, service, http_method, http_path, kind, enabled, result_schema)"
                + " VALUES ('导出任务', 'interface-doctor', 'POST', '/doctor/export-job', 'write', TRUE, '{}')");
        jdbc.update("UPDATE sys_api SET enabled = FALSE WHERE http_path = '/doctor/list'");

        // 写接口现在可以绑（2026-09-27 起取消旧 ADR-19 ②）：写操作的闸门是网关/接口服务各自消费的
        // 一次性 confirmId（§19.9），不是配置期的绑定禁令
        assertEquals(200, rebind(SKILL, List.of("interface-doctor POST /doctor/export-job")).getResponse().getStatus());
        assertEquals(List.of("interface-doctor POST /doctor/export-job"), boundRoutesViaApi(SKILL));
        assertEquals(true, jdbc.queryForObject("SELECT approved FROM skill_api", Boolean.class));

        // 两条口径不变：未注册 / 已停用
        assertEquals(400, rebind(SKILL, List.of("interface-doctor POST /doctor/missing")).getResponse().getStatus());
        assertEquals(400, rebind(SKILL, List.of("interface-doctor POST /doctor/list")).getResponse().getStatus());
        // 写法不合法按 400 回（fail-closed），而不是 500 或静默忽略这一项
        assertEquals(400, rebind(SKILL, List.of("/doctor/list")).getResponse().getStatus());
        // 一处不合法就整条拒绝：不做「合法的先绑上」，半套绑定比不改更难查
        assertEquals(
                400,
                rebind(SKILL, List.of("interface-doctor POST /doctor/export-job", "interface-doctor POST /doctor/missing"))
                        .getResponse()
                        .getStatus());
        // 技能不存在同样拒绝（别静默写 0 行还回「改绑成功」）
        assertEquals(400, rebind("no_such_skill", List.of("interface-doctor POST /doctor/performance"))
                .getResponse()
                .getStatus());

        // 上述失败请求一条都不许动库：库里仍是上一次成功写入的那份绑定
        assertEquals(List.of("interface-doctor POST /doctor/export-job"), boundRoutesViaApi(SKILL));
        assertEquals(1L, count("skill_api"));
    }

    @Test
    void publishingAPackageThatBindsAWriteApiSucceeds() throws Exception {
        jdbc.update("INSERT INTO sys_api (name, service, http_method, http_path, kind, enabled, result_schema)"
                + " VALUES ('导出任务', 'interface-doctor', 'POST', '/doctor/export-job', 'write', TRUE,"
                + " '{\"doctor_id\":{\"type\":\"string\"}}')");

        // 发布路径（manifest.boundRoutes）：写接口不再被自动驳回，跟着包一起标 approved
        publish(manifest("1.0.0", Map.of(
                "boundRoutes", List.of("interface-doctor POST /doctor/performance", "interface-doctor POST /doctor/export-job"),
                "exports", List.of("doctor_id"))));
        assertEquals("active", jdbc.queryForObject(
                "SELECT status FROM sys_skill WHERE skill_code = '" + SKILL + "'", String.class));
        assertEquals(List.of("interface-doctor POST /doctor/performance", "interface-doctor POST /doctor/export-job"),
                boundRoutesViaApi(SKILL));
        assertEquals(2L, count("skill_api"));
        assertEquals(0, readList(mvc.perform(get("/v1/admin/skill/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn()).size());
    }

    @Test
    void disablingKeepsThePackageAndRevokesTheBindings() throws Exception {
        Map<String, Object> uploaded = upload(manifest("1.0.0", Map.of()));
        long versionId = ((Number) version(uploaded).get("id")).longValue();
        mvc.perform(post("/v1/admin/skill/versions/" + versionId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}"))
                .andReturn();

        Map<String, Object> disabled = read(mvc.perform(put("/v1/admin/skill/" + SKILL + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andReturn());
        assertEquals(false, disabled.get("enabled"));
        assertEquals(true, disabled.get("updated"));
        assertEquals("disabled", jdbc.queryForObject(
                "SELECT status FROM sys_skill WHERE skill_code = '" + SKILL + "'", String.class));
        assertEquals(false, jdbc.queryForObject("SELECT approved FROM skill_api", Boolean.class));
        // 停用不删包：执行记录里的包哈希还要查得到（§19.2 可追溯）
        assertEquals("published", jdbc.queryForObject(
                "SELECT status FROM sys_skill_version WHERE id = " + versionId, String.class));
        assertNotEquals(null, lastAudit().get("before"));

        Map<String, Object> enabled = read(mvc.perform(put("/v1/admin/skill/" + SKILL + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andReturn());
        assertEquals(true, enabled.get("enabled"));
        assertEquals("active", jdbc.queryForObject(
                "SELECT status FROM sys_skill WHERE skill_code = '" + SKILL + "'", String.class));
        assertFalse(String.valueOf(lastAudit().get("after")).contains("disabled"));
    }

    /** 上传 + 批准：把技能发布出去（改绑的前提是技能行已经在 sys_skill 里）。 */
    private void publish(Map<String, Object> manifest) throws Exception {
        Map<String, Object> uploaded = upload(manifest);
        long versionId = ((Number) version(uploaded).get("id")).longValue();
        mvc.perform(post("/v1/admin/skill/versions/" + versionId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}"))
                .andReturn();
    }

    /** 管理端改绑端点：body 是**完整集合**（{"boundRoutes": [...]}），不是增量。 */
    private MvcResult rebind(String skillCode, List<String> routes) throws Exception {
        return mvc.perform(put("/v1/admin/skill/" + skillCode + "/apis")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("boundRoutes", routes))))
                .andReturn();
    }

    /** 读回该技能已审核的绑定（走管理端详情端点，即生产那条读路径）。 */
    @SuppressWarnings("unchecked")
    private List<String> boundRoutesViaApi(String skillCode) throws Exception {
        return (List<String>) read(mvc.perform(get("/v1/admin/skill/" + skillCode)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn())
                .get("boundRoutes");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static Map<String, Object> manifest(String version, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", SKILL);
        body.put("name", "医生收入查询");
        body.put("version", version);
        body.put("kind", "agentic");
        body.put("description", "按科室汇总医生收入");
        body.put("params", Map.of("month", Map.of("type", "string")));
        body.put("boundRoutes", List.of("interface-doctor POST /doctor/performance"));
        body.put("resources", List.of("README.md"));
        body.put("exports", List.of("doctor_id", "dept_code"));
        body.put("status", "draft");
        body.putAll(overrides);
        return body;
    }

    /** 最小 manifest：只写必填三项（§18.5.2 降门槛后就是这三项）。 */
    private static Map<String, Object> minimalManifest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", SKILL);
        body.put("name", "医生收入查询");
        body.put("description", "按科室汇总医生收入");
        return body;
    }

    /** 写全了但**不含** {@code boundRoutes} 的 manifest（「没写」与「写了空数组」是两回事）。 */
    private static Map<String, Object> manifestWithoutBindings(String version) {
        Map<String, Object> body = manifest(version, Map.of());
        body.remove("boundRoutes");
        return body;
    }

    /** 打一个最小可上传的技能包：{@code manifest.json} + 声明的资源文件。 */
    private static byte[] packageOf(Map<String, Object> manifest) throws Exception {
        return packageOf(manifest, Map.of());
    }

    private static byte[] packageOf(Map<String, Object> manifest, Map<String, String> textFiles) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(MAPPER.writeValueAsBytes(manifest));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write("# 医生收入查询".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, String> file : textFiles.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 造一个「连文件夹一起压缩」的包：所有条目都挂在一层顶层目录下（{@code manifest} 为 null 时不放清单）。
     *
     * <p>{@code textFiles} 的键是相对**包根**的路径，与手写 manifest 时的写法一致。
     */
    private static byte[] wrappedPackageOf(
            String folder, Map<String, Object> manifest, Map<String, String> textFiles) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(folder + "/"));
            zip.closeEntry();
            if (manifest != null) {
                zip.putNextEntry(new ZipEntry(folder + "/manifest.json"));
                zip.write(MAPPER.writeValueAsBytes(manifest));
                zip.closeEntry();
            }
            for (Map.Entry<String, String> file : textFiles.entrySet()) {
                zip.putNextEntry(new ZipEntry(folder + "/" + file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private Map<String, Object> upload(Map<String, Object> manifest) throws Exception {
        return upload(packageOf(manifest));
    }

    private Map<String, Object> upload(byte[] bytes) throws Exception {
        return read(mvc.perform(multipart("/v1/admin/skill/upload")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", bytes))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> version(Map<String, Object> uploadBody) {
        return (Map<String, Object>) uploadBody.get("version");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checks(Map<String, Object> uploadBody) {
        return (List<Map<String, Object>>) uploadBody.get("checks");
    }

    private static Map<String, Object> checkByCode(List<Map<String, Object>> checks, String code) {
        return checks.stream()
                .filter(check -> code.equals(check.get("code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少检查项 " + code + "，实际：" + checks));
    }

    private String token(String username, String password) throws Exception {
        Map<String, Object> body = Map.of("username", username, "password", password);
        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return String.valueOf(read(result).get("token"));
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private Map<String, Object> lastAudit() {
        return jdbc.queryForMap("SELECT * FROM config_audit ORDER BY id DESC LIMIT 1");
    }

    private static Map<String, Object> read(MvcResult result) throws Exception {
        return MAPPER.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), MAP_TYPE);
    }

    private static List<Map<String, Object>> readList(MvcResult result) throws Exception {
        return MAPPER.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
    }
}
