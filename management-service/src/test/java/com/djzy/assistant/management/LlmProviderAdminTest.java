package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 模型供应商配置（ADR-14 / §20.1.6 / §20.7）。
 *
 * <p>这一组用例的重点不是 CRUD 能不能跑，而是三条**不该被悄悄破坏**的性质：
 * <ol>
 *   <li>明文 Key 永远不出现在任何响应与任何审计里；
 *   <li>「同一时刻至多一个生效」由服务层真正维持；
 *   <li>每次变更都留下一条 {@code config_audit}，而非法输入一条都不留。
 * </ol>
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-llm;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret,
            "management-service.llm-kek=" + ManagementTestSupport.LLM_KEK
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class LlmProviderAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String URL = "/v1/admin/llm-provider";
    private static final String PLAINTEXT_KEY = "sk-test-0123456789abcdefghijklmn";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcLlmProviderStore store;

    private String adminToken;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM sys_llm_provider");
        jdbc.update("DELETE FROM config_audit");
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
    }

    @Test
    void onlyAdminCanManageProviders() throws Exception {
        assertEquals(401, statusOf(get(URL)));
        String alice = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
        assertEquals(403, statusOf(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(alice))));
        assertEquals(403, statusOf(put(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", PLAINTEXT_KEY, false)))));
        assertEquals(200, statusOf(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));
        assertEquals(0, auditCount());
    }

    @Test
    void savingAKeyStoresOnlyCiphertext() throws Exception {
        Map<String, Object> saved = read(perform(put(URL)
                .content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", PLAINTEXT_KEY, true)))));

        // 响应只给提示，不给明文
        assertEquals("sk-****klmn", saved.get("keyHint"));
        assertFalse(saved.toString().contains(PLAINTEXT_KEY), "响应体不得出现明文 Key");

        // 库里存的是密文，且不是明文本身
        String cipherText = jdbc.queryForObject(
                "SELECT api_key_cipher FROM sys_llm_provider WHERE provider_id = 'deepseek'", String.class);
        assertNotEquals(PLAINTEXT_KEY, cipherText);
        assertFalse(cipherText.contains(PLAINTEXT_KEY), "密文里不得出现明文片段");

        // agent 侧的读取出口能还原出明文（这正是它存在的唯一理由）
        assertEquals(
                PLAINTEXT_KEY,
                store.findEnabled().orElseThrow().apiKey());
    }

    @Test
    void plaintextKeyNeverReachesTheAuditTrail() throws Exception {
        perform(put(URL).content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", PLAINTEXT_KEY, true))));
        Map<String, Object> audit = lastAudit();
        assertEquals("llm_provider", audit.get("target"));
        assertEquals("llm_provider:deepseek", audit.get("field"));
        assertFalse(String.valueOf(audit.get("after")).contains(PLAINTEXT_KEY), "审计快照不得包含明文 Key");
        assertTrue(String.valueOf(audit.get("after")).contains("keyHint"));
    }

    @Test
    void enablingOneProviderDisablesTheOther() throws Exception {
        perform(put(URL).content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", "sk-aaaa1111", true))));
        perform(put(URL).content(json(provider("backup", "deepseek", "https://api.deepseek.com", "deepseek-v4-pro", "sk-bbbb2222", false))));

        Map<String, Object> promoted = read(perform(put(URL + "/backup/enabled").param("enabled", "true")));
        assertTrue((Boolean) promoted.get("enabled"));

        assertEquals(1, enabledCount(), "同一时刻至多一个生效");
        assertEquals("backup", jdbc.queryForObject(
                "SELECT provider_id FROM sys_llm_provider WHERE enabled = true", String.class));
        // 切换模型这件事本身也要留痕
        assertEquals("llm_provider:backup", lastAudit().get("field"));
    }

    @Test
    void updatingWithoutAKeyKeepsTheStoredOne() throws Exception {
        perform(put(URL).content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", PLAINTEXT_KEY, false))));
        String before = jdbc.queryForObject(
                "SELECT api_key_cipher FROM sys_llm_provider WHERE provider_id = 'deepseek'", String.class);

        Map<String, Object> saved = read(perform(put(URL)
                .content(json(provider("deepseek", "deepseek", "https://api.deepseek.com/v1", "deepseek-v4-pro", null, true)))));

        assertEquals("https://api.deepseek.com/v1", saved.get("baseUrl"));
        assertEquals("deepseek-v4-pro", saved.get("model"));
        assertEquals("sk-****klmn", saved.get("keyHint"), "未提供新 Key 时应保留原提示");
        assertEquals(before, jdbc.queryForObject(
                "SELECT api_key_cipher FROM sys_llm_provider WHERE provider_id = 'deepseek'", String.class));
    }

    @Test
    void invalidInputIsRejectedWithoutTouchingTheAuditLog() throws Exception {
        // 适配器不在白名单
        assertEquals(400, statusOf(put(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(provider("x", "no-such-adapter", "https://x", "m", "sk-x", false)))));
        // 端点不是 http(s)
        assertEquals(400, statusOf(put(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(provider("x", "deepseek", "ftp://api.deepseek.com", "deepseek-flash", "sk-x", false)))));
        // 模型名带冒号会把「适配器:模型」引用串拼坏
        assertEquals(400, statusOf(put(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(provider("x", "deepseek", "https://api.deepseek.com", "deepseek:chat", "sk-x", false)))));
        // 新增时必须带 Key
        assertEquals(400, statusOf(put(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(provider("x", "deepseek", "https://api.deepseek.com", "deepseek-flash", null, false)))));

        assertEquals(0, auditCount(), "被拒绝的写入不该留下审计");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sys_llm_provider", Integer.class));
    }

    @Test
    void deleteRemovesTheRowAndLeavesATrail() throws Exception {
        perform(put(URL).content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", PLAINTEXT_KEY, false))));
        Map<String, Object> body = read(perform(delete(URL + "/deepseek")));
        assertEquals(Boolean.TRUE, body.get("deleted"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sys_llm_provider", Integer.class));
        assertEquals("llm_provider:deepseek", lastAudit().get("field"));
        assertTrue(lastAudit().get("after") == null, "删除的审计快照 after 应为 null");
    }

    @Test
    void adaptersEndpointListsDeepSeekDefaults() throws Exception {
        List<Map<String, Object>> adapters = readList(perform(get(URL + "/adapters")));
        Map<String, Object> deepseek = adapters.stream()
                .filter(a -> "deepseek".equals(a.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("https://api.deepseek.com", deepseek.get("defaultBaseUrl"));
        assertEquals("deepseek-flash", deepseek.get("defaultModel"));
    }

    @Test
    void listingNeverExposesTheKey() throws Exception {
        perform(put(URL).content(json(provider("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", PLAINTEXT_KEY, true))));
        String body = mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(PLAINTEXT_KEY), "列表响应不得出现明文 Key");
        assertTrue(body.contains("sk-****klmn"));
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mvc.perform(builder
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertEquals(200, status, result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return result;
    }

    private static Map<String, Object> provider(
            String providerId, String adapter, String baseUrl, String model, String apiKey, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerId", providerId);
        body.put("adapter", adapter);
        body.put("baseUrl", baseUrl);
        body.put("model", model);
        body.put("apiKey", apiKey);
        body.put("enabled", enabled);
        return body;
    }

    private static String json(Map<String, Object> body) throws Exception {
        return MAPPER.writeValueAsString(body);
    }

    private int enabledCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sys_llm_provider WHERE enabled = true", Integer.class);
        return count == null ? 0 : count;
    }

    private int auditCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM config_audit", Integer.class);
        return count == null ? 0 : count;
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

    private int statusOf(MockHttpServletRequestBuilder builder) throws Exception {
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
