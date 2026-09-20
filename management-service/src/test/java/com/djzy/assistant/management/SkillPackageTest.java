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
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanReachTheSkillAdminSurface() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/skill")));
        assertEquals(403, statusOf(get("/v1/admin/skill").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
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
        assertEquals(true, checkByCode(checks, "WRITE_API_NOT_ALLOWED").get("passed"));
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

    @Test
    void blockingFailureIsAutoRejectedWithASystemReview() throws Exception {
        jdbc.update("INSERT INTO sys_api (name, service, http_method, http_path, kind, enabled, result_schema)"
                + " VALUES ('导出任务', 'interface-doctor', 'POST', '/doctor/export-job', 'write', TRUE,"
                + " '{\"doctor_id\":{\"type\":\"string\"}}')");

        Map<String, Object> uploaded = upload(manifest("1.0.0", Map.of("boundRoutes", List.of("interface-doctor POST /doctor/export-job"))));
        assertEquals("rejected", version(uploaded).get("status"));
        assertEquals(false, checkByCode(checks(uploaded), "WRITE_API_NOT_ALLOWED").get("passed"));
        assertTrue(String.valueOf(checkByCode(checks(uploaded), "BOUND_APIS_REGISTERED").get("passed")).equals("true"));

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

    /** 打一个最小可上传的技能包：{@code manifest.json} + 声明的资源文件。 */
    private static byte[] packageOf(Map<String, Object> manifest) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(MAPPER.writeValueAsBytes(manifest));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write("# 医生收入查询".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
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
