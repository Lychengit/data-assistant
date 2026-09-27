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
 * 角色/技能授权配置（§4.7 / §18.4.6 / §20.7）：**「哪些角色能用哪些技能」就落在 {@code role_skill} 上**。
 *
 * <p>这是本轮补上的那一块：原先只有「角色配接口」（{@code /v1/admin/role-api}），
 * 配技能只能手工往库里 {@code INSERT}——没有端点、没有界面、没有审计，也就管不住。
 *
 * <p>断言的分寸与 {@code RoleApiAdminTest} 一致：只有 admin 可改、每次改动一条 {@code config_audit}、
 * 改动零缓存即时生效、空操作不写审计。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-role-skill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class RoleSkillAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String SKILL = "doctor_perf_excel_report";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void seedSkillAndLogin() throws Exception {
        jdbc.update("DELETE FROM role_skill");
        jdbc.update("DELETE FROM config_audit");
        jdbc.update("DELETE FROM sys_skill");
        jdbc.update("INSERT INTO sys_skill (id, skill_code, name, status) VALUES (1, ?, '医生绩效报表', 'active')", SKILL);
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanReachTheConfigApi() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/role-skill").param("roleCode", "boss")));
        assertEquals(
                403,
                statusOf(get("/v1/admin/role-skill")
                        .param("roleCode", "boss")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
        assertEquals(
                403,
                statusOf(put("/v1/admin/role-skill")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grant("boss", SKILL, null))));

        assertEquals(0L, count("role_skill"));
        assertEquals(0L, count("config_audit"));
    }

    @Test
    void grantWritesAuditWithBeforeAndAfterAndIsIdempotent() throws Exception {
        Map<String, Object> created = putGrant("boss", SKILL, null, "req-create");
        assertEquals("boss", created.get("roleCode"));
        assertEquals(SKILL, created.get("skillCode"));
        assertEquals("医生绩效报表", created.get("skillName"));
        assertEquals(true, created.get("canView"));

        Map<String, Object> firstAudit = lastAudit();
        assertEquals("admin", firstAudit.get("who"));
        assertEquals("role", firstAudit.get("target"));
        assertEquals("role_skill:boss:" + SKILL, firstAudit.get("field"));
        assertEquals("req-create", firstAudit.get("request_id"));
        assertNull(firstAudit.get("before"));
        assertTrue(String.valueOf(firstAudit.get("after")).contains(SKILL));

        // 重复授权是空操作：不写审计（避免噪音），也不产生第二行
        putGrant("boss", SKILL, null, "req-again");
        assertEquals(1L, count("role_skill"));
        assertEquals("req-create", lastAudit().get("request_id"));
    }

    @Test
    void canViewCanBeTurnedOffAndThatChangeIsAudited() throws Exception {
        putGrant("boss", SKILL, null, "req-on");
        Map<String, Object> off = putGrant("boss", SKILL, false, "req-off");
        assertEquals(false, off.get("canView"));

        Map<String, Object> audit = lastAudit();
        assertEquals("req-off", audit.get("request_id"));
        assertNotNull(audit.get("before"));
        assertNotNull(audit.get("after"));
        // 授权行只有一行：改可见性是 UPDATE，不是再插一行
        assertEquals(1L, count("role_skill"));
    }

    @Test
    void listReturnsGrantsAndRevokeRemovesThem() throws Exception {
        putGrant("boss", SKILL, null, "req-1");

        List<Map<String, Object>> grants = readList(mvc.perform(get("/v1/admin/role-skill")
                        .param("roleCode", "boss")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(1, grants.size());
        assertEquals(SKILL, grants.get(0).get("skillCode"));

        Map<String, Object> revoked = read(mvc.perform(delete("/v1/admin/role-skill")
                        .param("roleCode", "boss")
                        .param("skillCode", SKILL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-revoke"))
                .andReturn());
        assertEquals(true, revoked.get("revoked"));
        assertEquals(0L, count("role_skill"));

        Map<String, Object> revokeAudit = lastAudit();
        assertEquals("req-revoke", revokeAudit.get("request_id"));
        assertNotNull(revokeAudit.get("before"));
        assertNull(revokeAudit.get("after"));

        // 重复撤销是空操作：不再写审计
        long audits = count("config_audit");
        Map<String, Object> again = read(mvc.perform(delete("/v1/admin/role-skill")
                        .param("roleCode", "boss")
                        .param("skillCode", SKILL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(false, again.get("revoked"));
        assertEquals(audits, count("config_audit"));
    }

    @Test
    void unknownRoleOrSkillAreRejectedWithoutSideEffects() throws Exception {
        // 技能不存在
        assertEquals(400, reject(grant("boss", "no_such_skill", null)));
        // 角色不存在
        assertEquals(400, reject(grant("no_such_role", SKILL, null)));
        // 缺 skillCode：凭角色名授权等于放开全部技能
        assertEquals(
                400,
                statusOf(put("/v1/admin/role-skill")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"boss\"}")));

        assertEquals(0L, count("role_skill"));
        assertEquals(0L, count("config_audit"));
    }

    @Test
    void grantsAreVisibleToThePermissionRepositoryImmediately() throws Exception {
        putGrant("boss", SKILL, null, "req-1");
        List<Long> skillIds = jdbc.queryForList(
                "SELECT rs.skill_id FROM role_skill rs JOIN sys_role r ON r.id = rs.role_id WHERE r.role_code = 'boss'",
                Long.class);
        assertEquals(List.of(1L), skillIds);
        assertFalse(skillIds.isEmpty());
    }

    private Map<String, Object> putGrant(String roleCode, String skillCode, Boolean canView, String requestId)
            throws Exception {
        return read(mvc.perform(put("/v1/admin/role-skill")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grant(roleCode, skillCode, canView)))
                .andReturn());
    }

    private int reject(String payload) throws Exception {
        return statusOf(put("/v1/admin/role-skill")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private static String grant(String roleCode, String skillCode, Boolean canView) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleCode", roleCode);
        body.put("skillCode", skillCode);
        if (canView != null) {
            body.put("canView", canView);
        }
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