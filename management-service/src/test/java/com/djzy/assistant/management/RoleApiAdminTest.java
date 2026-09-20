package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * M2 角色/接口授权配置（§18.4.6 / §20.7）：只有 admin 可改；每次改动一条 {@code config_audit}；
 * 改动即时生效（零缓存，不重启、不等缓存过期）。
 *
 * <p>授权行现在是**纯关联**：{@code (role_id, api_id)}，接口用 {@code apiId} 指代。
 * 请求体里没有 {@code scope}——数据范围不由授权承载（§19.1：范围由接口服务基于登录人自己推导）。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class RoleApiAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /** 演示种子 sys_api.id = 1（interface-doctor POST /doctor/performance）。 */
    private static final long PERFORMANCE = 1L;
    private static final long DOCTOR_LIST = 2L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void loginBoth() throws Exception {
        jdbc.update("DELETE FROM role_api");
        jdbc.update("DELETE FROM config_audit");
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanReachTheConfigApi() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/role-api").param("roleCode", "boss")));
        assertEquals(403, statusOf(get("/v1/admin/role-api").param("roleCode", "boss").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
        assertEquals(403, statusOf(put("/v1/admin/role-api")
                .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(grant("boss", PERFORMANCE))));

        assertEquals(0L, count("role_api"));
        assertEquals(0L, count("config_audit"));
    }

    @Test
    void grantWritesAuditWithBeforeAndAfterAndIsIdempotent() throws Exception {
        Map<String, Object> created = putGrant("boss", PERFORMANCE, "req-create");
        assertEquals("boss", created.get("roleCode"));
        assertEquals(PERFORMANCE, ((Number) created.get("apiId")).longValue());
        assertEquals("POST", created.get("httpMethod"));
        assertEquals("/doctor/performance", created.get("httpPath"));
        // 展示用的一行式标识：授权行看路径，不用再去猜一个 id
        assertEquals("POST /doctor/performance", created.get("route"));

        Map<String, Object> firstAudit = lastAudit();
        assertEquals("admin", firstAudit.get("who"));
        assertEquals("role", firstAudit.get("target"));
        assertEquals("role_api:boss:POST /doctor/performance", firstAudit.get("field"));
        assertEquals("req-create", firstAudit.get("request_id"));
        assertNull(firstAudit.get("before"));
        assertTrue(String.valueOf(firstAudit.get("after")).contains("/doctor/performance"));

        // 重复授权是空操作：不写审计（避免噪音），也不产生第二行
        putGrant("boss", PERFORMANCE, "req-again");
        assertEquals(1L, count("role_api"));
        assertEquals("req-create", lastAudit().get("request_id"));
    }

    @Test
    void listReturnsGrantsAndRevokeRemovesThem() throws Exception {
        putGrant("boss", PERFORMANCE, "req-1");
        putGrant("boss", DOCTOR_LIST, "req-2");

        List<Map<String, Object>> grants = readList(mvc.perform(
                        get("/v1/admin/role-api").param("roleCode", "boss").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(2, grants.size());
        assertTrue(grants.stream().anyMatch(g -> "/doctor/performance".equals(g.get("httpPath"))));
        assertTrue(grants.stream().anyMatch(g -> "/doctor/list".equals(g.get("httpPath"))));

        Map<String, Object> revoked = read(mvc.perform(delete("/v1/admin/role-api")
                        .param("roleCode", "boss")
                        .param("apiId", String.valueOf(PERFORMANCE))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-revoke"))
                .andReturn());
        assertEquals(true, revoked.get("revoked"));
        assertEquals(1L, count("role_api"));

        Map<String, Object> revokeAudit = lastAudit();
        assertEquals("req-revoke", revokeAudit.get("request_id"));
        assertNotNull(revokeAudit.get("before"));
        assertNull(revokeAudit.get("after"));

        // 重复撤销是空操作：不再写审计（避免噪音）
        long audits = count("config_audit");
        Map<String, Object> again = read(mvc.perform(delete("/v1/admin/role-api")
                        .param("roleCode", "boss")
                        .param("apiId", String.valueOf(PERFORMANCE))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(false, again.get("revoked"));
        assertEquals(audits, count("config_audit"));
    }

    @Test
    void unknownRoleOrApiAreRejectedWithoutSideEffects() throws Exception {
        // 接口未注册
        assertEquals(400, reject(grant("boss", 999L)));
        // 角色不存在
        assertEquals(400, reject(grant("no_such_role", PERFORMANCE)));
        // 缺 apiId：授权必须指向一个具体接口，凭角色名授权等于放开全部
        assertEquals(400, statusOf(put("/v1/admin/role-api")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCode\":\"boss\"}")));

        assertEquals(0L, count("role_api"));
        assertEquals(0L, count("config_audit"));
    }

    @Test
    void grantsAreVisibleToTheAuthorizationServiceImmediately() throws Exception {
        putGrant("boss", PERFORMANCE, "req-1");
        List<Long> apiIds = jdbc.queryForList(
                "SELECT api_id FROM role_api ra JOIN sys_role r ON r.id = ra.role_id WHERE r.role_code = 'boss'",
                Long.class);
        assertEquals(List.of(PERFORMANCE), apiIds);
        assertFalse(apiIds.isEmpty());
    }

    private Map<String, Object> putGrant(String roleCode, long apiId, String requestId) throws Exception {
        return read(mvc.perform(put("/v1/admin/role-api")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grant(roleCode, apiId)))
                .andReturn());
    }

    private int reject(String payload) throws Exception {
        return statusOf(put("/v1/admin/role-api")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private static String grant(String roleCode, long apiId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleCode", roleCode);
        body.put("apiId", apiId);
        return MAPPER.writeValueAsString(body);
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
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

    private static List<Map<String, Object>> readList(MvcResult result) throws Exception {
        return MAPPER.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
    }
}