package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.djzy.assistant.common.identity.HmacJwt;
import com.djzy.assistant.common.identity.UserTokenClaims;
import com.djzy.assistant.management.ManagementTestSupport.H2DialectConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M1 登录与令牌（§18.4.6 / §19.4）：JWT ≤15 分钟、刷新令牌一次性轮换、失败统一 401。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class AuthFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    MockMvc mvc;

    @Test
    void loginIssuesShortLivedTokenWithRoles() throws Exception {
        Map<String, Object> body = login(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);

        String token = String.valueOf(body.get("token"));
        assertEquals("Bearer", body.get("tokenType"));
        assertEquals(900, number(body, "expiresIn"));
        assertEquals("admin", body.get("userId"));
        assertEquals("系统管理员", body.get("displayName"));
        assertEquals(List.of("admin"), body.get("roles"));
        assertTrue(String.valueOf(body.get("refreshToken")).length() > 20);

        UserTokenClaims claims = HmacJwt.verify(ManagementTestSupport.jwtSecret, token).orElseThrow();
        assertEquals("admin", claims.subject());
        assertTrue(Duration.between(claims.issuedAt(), claims.expiresAt()).compareTo(Duration.ofMinutes(15)) <= 0);
    }

    @Test
    void loginFailureIsUniformlyUnauthorized() throws Exception {
        assertEquals(401, loginStatus(ManagementTestSupport.ADMIN, "wrong-password"));
        assertEquals(401, loginStatus("nobody", "whatever"));
        assertEquals(401, loginStatus("gone", ManagementTestSupport.ALICE_PASSWORD));
        assertEquals(401, loginStatus(null, null));
        assertTrue(loginBody("nobody", "whatever").contains("未认证的请求"));
    }

    @Test
    void meRequiresValidTokenAndReadsRolesFromPermissionDb() throws Exception {
        String token = String.valueOf(login(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD).get("token"));

        assertEquals(401, statusOf(get("/v1/auth/me")));
        assertEquals(401, statusOf(get("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")));

        Map<String, Object> me = read(mvc.perform(
                        get("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn());
        assertEquals("alice", me.get("userId"));
        assertEquals(List.of("boss"), me.get("roles"));
    }

    @Test
    void refreshRotatesAndInvalidatesTheOldToken() throws Exception {
        Map<String, Object> first = login(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        String firstRefresh = String.valueOf(first.get("refreshToken"));

        Map<String, Object> rotated = read(mvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("refreshToken", firstRefresh))))
                .andReturn());
        assertNotEquals(first.get("token"), rotated.get("token"));
        assertNotEquals(firstRefresh, rotated.get("refreshToken"));
        assertNotNull(rotated.get("token"));

        // 旧刷新令牌已被一次性消费：重放必须 401（§19.4 防重放）
        assertEquals(401, refreshStatus(firstRefresh));
        assertEquals(401, refreshStatus("garbage"));
        assertEquals(401, refreshStatus(null));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String refreshToken =
                String.valueOf(login(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD).get("refreshToken"));

        mvc.perform(post("/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();

        assertEquals(401, refreshStatus(refreshToken));
    }

    private Map<String, Object> login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(credentials(username, password))))
                .andReturn();
        assertEquals(
                200,
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return read(result);
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(credentials(username, password))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String loginBody(String username, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(credentials(username, password))))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private int refreshStatus(String refreshToken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refreshToken", refreshToken);
        return mvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private static Map<String, Object> credentials(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    private static int number(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).intValue();
    }

    private static Map<String, Object> read(MvcResult result) throws Exception {
        return MAPPER.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), MAP_TYPE);
    }
}
