package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.djzy.assistant.management.ManagementTestSupport.H2DialectConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M4 接口注册（§18.4.6 / §20.7）：只有 admin 可写；每次登记/变更一条 {@code config_audit}。
 *
 * <p>接口身份是**三元组**（服务 + 方法 + 路径），登记请求体里既没有 {@code apiCode}、也没有
 * {@code columnWhitelist}；行号由库分配（{@code id}），管理端后续按 id 启停/删除。
 * 模型侧工具名由路径派生，管理端只读展示——不存在"配置的名字与实际工具名不一致"。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class ApiAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String SERVICE = "lab-service";
    private static final String METHOD = "POST";
    private static final String PATH = "/lab/report";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM role_api");
        jdbc.update("DELETE FROM config_audit");
        // 演示种子（id 1/2）保留：接口服务的启动自检要求注册表与代码一致
        jdbc.update("DELETE FROM sys_api WHERE id NOT IN (1, 2)");
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanRegisterApis() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/api")));
        assertEquals(403, statusOf(get("/v1/admin/api").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
        assertEquals(403, statusOf(put("/v1/admin/api")
                .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(registration(SERVICE, METHOD, PATH, "检验报告", "read"))));

        assertEquals(0L, count("config_audit"));
        assertEquals(0L, apiRowsAt(PATH));
    }

    @Test
    void registrationIsImmediatelyVisibleAndWritesAuditWithBeforeAndAfter() throws Exception {
        Map<String, Object> created = register(SERVICE, METHOD, PATH, "检验报告", "read", "req-api-1");
        assertEquals(SERVICE, created.get("service"));
        assertEquals(METHOD, created.get("httpMethod"));
        assertEquals(PATH, created.get("httpPath"));
        // 工具名是派生的、只读展示：/lab/report → iface_lab_report
        assertEquals("iface_lab_report", created.get("toolName"));
        long id = idOf(created);

        // 登记即时生效：GET 立即可见（零缓存）
        Map<String, Object> fetched = read(mvc.perform(get("/v1/admin/api/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(SERVICE, fetched.get("service"));
        assertTrue((Boolean) fetched.get("enabled"));

        Map<String, Object> firstAudit = lastAudit();
        assertEquals("admin", firstAudit.get("who"));
        assertEquals("api", firstAudit.get("target"));
        assertEquals("sys_api:lab-service POST /lab/report", firstAudit.get("field"));
        assertEquals("req-api-1", firstAudit.get("request_id"));
        assertNull(firstAudit.get("before"));
        assertTrue(String.valueOf(firstAudit.get("after")).contains("检验报告"));

        // 同一三元组再登记 = 原地更新（不是插第二行）
        register(SERVICE, METHOD, PATH, "检验报告 v2", "write", "req-api-2");
        Map<String, Object> updateAudit = lastAudit();
        assertEquals("req-api-2", updateAudit.get("request_id"));
        assertTrue(String.valueOf(updateAudit.get("before")).contains("检验报告"));
        assertTrue(String.valueOf(updateAudit.get("after")).contains("检验报告 v2"));
        assertEquals("write", read(mvc.perform(get("/v1/admin/api/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn()).get("kind"));
        assertEquals(1L, apiRowsAt(PATH));
    }

    @Test
    void malformedRegistrationsAreRejected() throws Exception {
        // 缺服务名 / 缺路径 / 缺名称
        assertEquals(400, reject(registration(null, METHOD, PATH, "检验报告", "read")));
        assertEquals(400, reject(registration(SERVICE, METHOD, null, "检验报告", "read")));
        assertEquals(400, reject(registration(SERVICE, METHOD, PATH, null, "read")));
        // 路径必须落在白名单形态内：有前导斜杠、无 .. 与 //
        assertEquals(400, reject(registration(SERVICE, METHOD, "/lab/../etc/passwd", "检验报告", "read")));
        assertEquals(400, reject(registration(SERVICE, METHOD, "/lab//report", "检验报告", "read")));
        assertEquals(400, reject(registration(SERVICE, METHOD, "lab/report", "检验报告", "read")));
        // 副作用等级只允许 read/write
        assertEquals(400, reject(registration(SERVICE, METHOD, PATH, "检验报告", "delete")));
        // 服务名必须是小写字母开头的 DNS 风格（网关按它找目标服务）
        assertEquals(400, reject(registration("Lab-Service", METHOD, PATH, "检验报告", "read")));
        // 同一「方法 + 路径」已被别的服务占用 → 工具名会撞车，宁可不注册
        assertEquals(400, reject(registration(SERVICE, METHOD, "/doctor/list", "医生列表（重复）", "read")));

        assertEquals(0L, count("config_audit"));
        assertEquals(0L, apiRowsAt(PATH));
        assertEquals(1L, apiRowsAt("/doctor/list"));
    }

    @Test
    void disableTakesEffectAndIsAuditedOnlyWhenChanged() throws Exception {
        long id = idOf(register(SERVICE, METHOD, PATH, "检验报告", "read", "req-a"));
        long audits = count("config_audit");

        Map<String, Object> disabled = read(mvc.perform(put("/v1/admin/api/" + id + "/enabled")
                        .param("enabled", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-disable"))
                .andReturn());
        assertFalse((Boolean) disabled.get("enabled"));
        assertEquals(audits + 1, count("config_audit"));
        assertEquals("req-disable", lastAudit().get("request_id"));

        // 状态没变 → 不写审计（避免噪音）
        mvc.perform(put("/v1/admin/api/" + id + "/enabled")
                        .param("enabled", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn();
        assertEquals(audits + 1, count("config_audit"));
    }

    @Test
    void grantedApisCannotBeDeletedButCanBeRevokedThenDeleted() throws Exception {
        long id = idOf(register(SERVICE, METHOD, PATH, "检验报告", "read", "req-a"));
        mvc.perform(put("/v1/admin/role-api")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"boss\",\"apiId\":" + id + "}"))
                .andReturn();
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM role_api", Long.class) > 0);

        // 被授权 → 不许删（授权悬空比「多停用一个接口」危险）
        assertEquals(400, statusOf(delete("/v1/admin/api/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));

        mvc.perform(delete("/v1/admin/role-api").param("roleCode", "boss").param("apiId", String.valueOf(id))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn();
        Map<String, Object> deleted = read(mvc.perform(delete("/v1/admin/api/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-del"))
                .andReturn());
        assertEquals(true, deleted.get("deleted"));
        assertNull(lastAudit().get("after"));
        assertEquals("req-del", lastAudit().get("request_id"));
        assertEquals(0L, apiRowsAt(PATH));
    }

    /**
     * 场景与返回字段契约要能从管理端录进去、再原样取回来（§4.8 节点 A）。
     *
     * <p>它们是模型侧工具描述的两段正文：少了「什么时候用」模型会在相似接口间乱选，
     * 少了「返回哪些字段」只能等结果回来才知道。管理端录不进去，就等于这两段永远写不上。
     */
    @Test
    void scenarioAndResultSchemaRoundTripThroughAdminApi() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", SERVICE);
        body.put("httpMethod", METHOD);
        body.put("httpPath", PATH);
        body.put("name", "检验报告");
        body.put("kind", "read");
        body.put("resource", "lab");
        body.put("paramSchema", Map.of("month", "string"));
        body.put("enabled", true);
        body.put("scenario", "查某月的检验报告时用它；问「有哪些医生」不要用它");
        body.put("resultSchema", Map.of("report_no", Map.of("type", "string", "description", "报告号")));

        Map<String, Object> created = read(mvc.perform(put("/v1/admin/api")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn());
        long id = idOf(created);

        Map<String, Object> fetched = read(mvc.perform(get("/v1/admin/api/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals("查某月的检验报告时用它；问「有哪些医生」不要用它", fetched.get("scenario"));
        assertEquals(
                Map.of("report_no", Map.of("type", "string", "description", "报告号")),
                fetched.get("resultSchema"));
        // 审计快照也要带上：否则改错了「场景」在审计里看不出来
        assertTrue(String.valueOf(lastAudit().get("after")).contains("scenario"));
    }

    private Map<String, Object> register(
            String service, String method, String path, String name, String kind, String requestId) throws Exception {
        MvcResult result = mvc.perform(put("/v1/admin/api")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration(service, method, path, name, kind)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return read(result);
    }

    private int reject(String payload) throws Exception {
        return statusOf(put("/v1/admin/api")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private static String registration(String service, String method, String path, String name, String kind)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", service);
        body.put("httpMethod", method);
        body.put("httpPath", path);
        body.put("name", name);
        body.put("kind", kind);
        body.put("paramSchema", Map.of());
        return MAPPER.writeValueAsString(body);
    }

    private static long idOf(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private long apiRowsAt(String path) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM sys_api WHERE http_path = ?", Long.class, path);
        return value == null ? 0 : value;
    }

    private String token(String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return String.valueOf(read(result).get("token"));
    }

    private Map<String, Object> lastAudit() {
        return jdbc.queryForMap("SELECT * FROM config_audit ORDER BY id DESC LIMIT 1");
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static Map<String, Object> read(MvcResult result) throws Exception {
        return MAPPER.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), MAP_TYPE);
    }
}