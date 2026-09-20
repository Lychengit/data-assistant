package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * M5 口径字典（§6.1 / §18.4.6 / §20.7）：只有 admin 可改；每次改动一条 {@code config_audit}；
 * 精度、单位换算、派生算子都必须**进库前就合法**（否则数字复算算不出来，§6.3）。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-metric;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class MetricAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String METRIC = "profit";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM metric_dictionary");
        jdbc.update("DELETE FROM unit_convert");
        jdbc.update("DELETE FROM config_audit");
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
    }

    @Test
    void onlyAdminCanEditTheDictionary() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/metric")));
        assertEquals(403, statusOf(get("/v1/admin/metric").header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));
        assertEquals(403, statusOf(put("/v1/admin/metric")
                .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(metricBody(Map.of("metricKey", METRIC, "name", "利润")))));

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM metric_dictionary", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM config_audit", Long.class));
    }

    @Test
    void upsertKeepsEverySpecFieldAndRecordsBeforeAfter() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metricKey", METRIC);
        body.put("domain", "doctor_performance");
        body.put("name", "利润");
        body.put("aliases", List.of("净利润", "利润额", "净利润"));
        body.put("definition", "收入减成本");
        body.put("formula", "利润 = 收入 - 成本");
        body.put("timeBasis", "natural_month");
        body.put("unit", "元");
        body.put("scale", 10000);
        body.put("rounding", 2);
        body.put("derivedOf", Map.of("sources", List.of("gmv", "cost"), "operator", "diff", "basis", "month_over_month"));
        body.put("timezone", "Asia/Shanghai");
        body.put("basis", "财务口径");

        Map<String, Object> created = read(mvc.perform(put("/v1/admin/metric")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-metric-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn());
        assertEquals(METRIC, created.get("metricKey"));
        // 别名去重（同一别名出现两次只留一个）
        assertEquals(List.of("净利润", "利润额"), created.get("aliases"));
        assertEquals("DIFF", ((Map<?, ?>) created.get("derivedOf")).get("operator"));
        assertEquals(2, created.get("rounding"));

        Map<String, Object> firstAudit = lastAudit();
        assertEquals("admin", firstAudit.get("who"));
        assertEquals("dictionary", firstAudit.get("target"));
        assertEquals("metric_dictionary:" + METRIC, firstAudit.get("field"));
        assertNull(firstAudit.get("before"));
        assertTrue(String.valueOf(firstAudit.get("after")).contains("财务口径"));

        body.put("basis", "财务口径 v2");
        mvc.perform(put("/v1/admin/metric")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-metric-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andReturn();
        Map<String, Object> updateAudit = lastAudit();
        assertEquals("req-metric-2", updateAudit.get("request_id"));
        assertTrue(String.valueOf(updateAudit.get("before")).contains("财务口径"));
        assertTrue(String.valueOf(updateAudit.get("after")).contains("财务口径 v2"));
    }

    @Test
    void invalidDefinitionsAreRejectedBeforeTheyReachTheDatabase() throws Exception {
        // 精度越界：容差无从谈起
        assertEquals(400, statusOf(put("/v1/admin/metric")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(metricBody(Map.of("metricKey", METRIC, "name", "利润", "rounding", 9)))));
        // 时区不存在：区间会算错
        assertEquals(400, statusOf(put("/v1/admin/metric")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(metricBody(Map.of("metricKey", METRIC, "name", "利润", "timezone", "Mars/Phobos")))));
        // 派生算子在白名单外（与算式复算共用同一份白名单，§6.3）
        assertEquals(400, statusOf(put("/v1/admin/metric")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(metricBody(Map.of(
                        "metricKey", METRIC,
                        "name", "利润",
                        "derivedOf", Map.of("sources", List.of("gmv"), "operator", "MAGIC"))))));
        // 派生声明里的未知字段
        assertEquals(400, statusOf(put("/v1/admin/metric")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(metricBody(Map.of(
                        "metricKey", METRIC,
                        "name", "利润",
                        "derivedOf", Map.of("sources", List.of("gmv"), "operator", "DIFF", "sql", "drop table x"))))));
        // 单位换算系数必须为正
        assertEquals(400, statusOf(put("/v1/admin/metric/unit-convert")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromUnit\":\"万\",\"toUnit\":\"元\",\"factor\":0}")));

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM metric_dictionary", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM config_audit", Long.class));
    }

    @Test
    void enableDisableDeleteAndUnitConversionAllLeaveTraces() throws Exception {
        MvcResult createResult = mvc.perform(put("/v1/admin/metric")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricBody(Map.of("metricKey", METRIC, "name", "利润"))))
                .andReturn();
        assertEquals(200, createResult.getResponse().getStatus(), createResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long audits = auditCount();

        MvcResult disableResult = mvc.perform(put("/v1/admin/metric/" + METRIC + "/enabled")
                        .param("enabled", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-disable"))
                .andReturn();
        assertEquals(200, disableResult.getResponse().getStatus(), disableResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Map<String, Object> disabled = read(disableResult);
        assertEquals(false, disabled.get("enabled"));
        assertEquals(audits + 1, auditCount());

        // 状态没变 → 不重复记账
        mvc.perform(put("/v1/admin/metric/" + METRIC + "/enabled")
                        .param("enabled", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn();
        assertEquals(audits + 1, auditCount());

        Map<String, Object> conversion = read(mvc.perform(put("/v1/admin/metric/unit-convert")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-unit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromUnit\":\"万\",\"toUnit\":\"元\",\"factor\":10000}"))
                .andReturn());
        assertEquals("万", conversion.get("fromUnit"));
        assertEquals("req-unit", lastAudit().get("request_id"));
        List<Map<String, Object>> conversions = readList(mvc.perform(get("/v1/admin/metric/unit-convert")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(1, conversions.size());

        Map<String, Object> deleted = read(mvc.perform(delete("/v1/admin/metric/" + METRIC)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header("X-Request-Id", "req-del"))
                .andReturn());
        assertEquals(true, deleted.get("deleted"));
        assertNull(lastAudit().get("after"));
        assertEquals("req-del", lastAudit().get("request_id"));
    }

    private static String metricBody(Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.putIfAbsent("rounding", 1);
        return MAPPER.writeValueAsString(body);
    }

    private long auditCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM config_audit", Long.class);
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
