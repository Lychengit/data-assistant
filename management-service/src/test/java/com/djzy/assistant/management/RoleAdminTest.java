package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * 角色只读清单（{@code GET /v1/admin/role}）。
 *
 * <p>它补的是管理端「角色 × 技能 / 角色 × 接口」两张配置页的**主语来源**：
 * 在此之前页面只能让人手敲 roleCode，敲错的表现是"这个角色一条授权都没有"，看不出是自己填错了。
 *
 * <p>断言的分寸：只有 admin 能读、给的是 {@code sys_role} 的真实内容（不是硬编码枚举）、
 * 新增角色立刻可见（零缓存，§0.3-4）。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-role;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class RoleAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void login() throws Exception {
        jdbc.update("DELETE FROM sys_role WHERE id > 2");
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanReadTheRoleList() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/role")));
        assertEquals(
                403,
                statusOf(get("/v1/admin/role").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
        assertEquals(
                200,
                statusOf(get("/v1/admin/role").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));
    }

    @Test
    void listCarriesCodeNameAndRemarkInIdOrder() throws Exception {
        List<Map<String, Object>> roles =
                readList(mvc.perform(get("/v1/admin/role").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                        .andReturn());
        assertEquals(List.of("admin", "boss"), roles.stream().map(role -> role.get("roleCode")).toList());
        assertEquals("系统管理员", roles.get(0).get("roleName"));
        assertEquals("全部权限，含管理配置", roles.get(0).get("remark"));
        assertEquals("上级领导", roles.get(1).get("roleName"));
    }

    @Test
    void aNewlyInsertedRoleShowsUpWithoutRestart() throws Exception {
        jdbc.update("INSERT INTO sys_role (id, role_code, role_name, remark) VALUES (99, 'newcomer', '新角色', NULL)");
        List<Map<String, Object>> roles =
                readList(mvc.perform(get("/v1/admin/role").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                        .andReturn());
        Map<String, Object> added = roles.stream()
                .filter(role -> "newcomer".equals(role.get("roleCode")))
                .findFirst()
                .orElseThrow();
        assertEquals("新角色", added.get("roleName"));
        assertNull(added.get("remark"));
        assertTrue(roles.size() >= 3);
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
        return String.valueOf(
                MAPPER.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), MAP_TYPE)
                        .get("token"));
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static List<Map<String, Object>> readList(MvcResult result) throws Exception {
        return MAPPER.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), LIST_TYPE);
    }
}
