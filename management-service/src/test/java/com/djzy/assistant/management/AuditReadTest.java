package com.djzy.assistant.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.djzy.assistant.management.ManagementTestSupport.H2DialectConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;

/**
 * M6 审计 / 回放 / 监控（§18.4.6 / §20.4 / §20.5）。
 *
 * <p>四条硬口径：① 只有 admin 能进（非 admin 的尝试也留 DENY 记录）；
 * ② **审计读本身要记一条审计**；③ 查询必须带时间范围（左闭右开、UTC）；
 * ④ 按 requestId 回放，agent 侧与接口服务侧**不一致即告警**。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mgmt-audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "management-service.secrets.login-token=" + ManagementTestSupport.jwtSecret
        })
@AutoConfigureMockMvc
@Import(H2DialectConfig.class)
class AuditReadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String TRACE = "trace-1";
    private static final String REQUEST = "req-1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String adminToken;
    private String aliceToken;
    private Instant now;

    @BeforeEach
    void reset() throws Exception {
        for (String table : List.of(
                "data_access_audit", "permission_audit", "audit_read_audit", "config_audit",
                "tool_call", "llm_call", "agent_step", "conversation_turn")) {
            jdbc.update("DELETE FROM " + table);
        }
        adminToken = token(ManagementTestSupport.ADMIN, ManagementTestSupport.ADMIN_PASSWORD);
        aliceToken = token(ManagementTestSupport.ALICE, ManagementTestSupport.ALICE_PASSWORD);
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    void auditQueriesRequireATimeRange() throws Exception {
        // 审计长期保留（§20.5）：不套时间范围会全表扫，一律拒绝
        assertEquals(400, statusOf(get("/v1/admin/audit/data-access")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));
        assertEquals(400, statusOf(get("/v1/admin/audit/overview")
                .param("from", iso(-3600))
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));
        // 左闭右开：from 必须早于 to
        assertEquals(400, statusOf(get("/v1/admin/audit/overview")
                .param("from", iso(60))
                .param("to", iso(-60))
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));
        // 单次窗口有上限
        assertEquals(400, statusOf(get("/v1/admin/audit/overview")
                .param("from", now.minus(90, ChronoUnit.DAYS).toString())
                .param("to", now.toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))));
    }

    @Test
    void onlyAdminCanReadAuditAndFailedAttemptsAreRecorded() throws Exception {
        assertEquals(401, statusOf(get("/v1/admin/audit/overview").param("from", iso(-60)).param("to", iso(60))));
        assertEquals(403, statusOf(get("/v1/admin/audit/overview")
                .param("from", iso(-60))
                .param("to", iso(60))
                .header(HttpHeaders.AUTHORIZATION, bearer(aliceToken))));

        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM audit_read_audit", Long.class));
        Map<String, Object> denied = jdbc.queryForMap("SELECT * FROM audit_read_audit ORDER BY id DESC LIMIT 1");
        assertEquals("alice", denied.get("who"));
        assertEquals("DENY", denied.get("outcome"));
        assertEquals("overview", denied.get("action"));
    }

    @Test
    void auditReadItselfIsAuditedWithQueryWindow() throws Exception {
        seedTraceData();
        List<Map<String, Object>> rows = readList(mvc.perform(get("/v1/admin/audit/data-access")
                        .param("from", iso(-3600))
                        .param("to", iso(3600))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(2, rows.size());

        // 审计读留痕：谁、查了什么、拿到几行
        Map<String, Object> read1 = jdbc.queryForMap("SELECT * FROM audit_read_audit ORDER BY id DESC LIMIT 1");
        assertEquals("admin", read1.get("who"));
        assertEquals("data-access", read1.get("action"));
        assertEquals("ALLOW", read1.get("outcome"));
        assertEquals(2, ((Number) read1.get("row_count")).intValue());
        assertTrue(String.valueOf(read1.get("params")).contains("from"));

        // 审计读记录本身也能被查（谁在何时查了审计，§20.4）
        List<Map<String, Object>> reads = readList(mvc.perform(get("/v1/admin/audit/reads")
                        .param("from", iso(-3600))
                        .param("to", iso(3600))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());
        assertEquals(1, reads.size());
        assertEquals("data-access", reads.get(0).get("action"));
        assertEquals("admin", reads.get(0).get("who"));
    }

    @Test
    void traceReplaysTheWholeChainAndWarnsAboutMismatches() throws Exception {
        seedTraceData();
        Map<String, Object> chain = read(mvc.perform(get("/v1/admin/audit/trace")
                        .param("from", iso(-3600))
                        .param("to", iso(3600))
                        .param("requestId", REQUEST)
                        .param("traceId", TRACE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());

        assertEquals(2, ((List<?>) chain.get("dataAccess")).size());
        assertEquals(3, ((List<?>) chain.get("toolCalls")).size());
        assertEquals(3, ((List<?>) chain.get("llmCalls")).size());
        assertEquals(1, ((List<?>) chain.get("permissionAudits")).size());
        assertEquals(1, ((List<?>) chain.get("turns")).size());

        List<Map<String, Object>> mismatches = (List<Map<String, Object>>) chain.get("mismatches");
        assertTrue(mismatches.stream().anyMatch(m -> "AGENT_IFACE_CALL_WITHOUT_DATA_ACCESS_AUDIT".equals(m.get("code"))));
        assertTrue(mismatches.stream().anyMatch(m -> "DATA_ACCESS_NOT_ALLOWED".equals(m.get("code"))));

        // 回放本身也是一次审计读
        assertEquals("trace", jdbc.queryForMap("SELECT * FROM audit_read_audit ORDER BY id DESC LIMIT 1").get("action"));
    }

    @Test
    void overviewReportsBlocksLatencyCostAndDriftSignals() throws Exception {
        seedTraceData();
        Map<String, Object> overview = read(mvc.perform(get("/v1/admin/audit/overview")
                        .param("from", iso(-3600))
                        .param("to", iso(3600))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andReturn());

        Map<String, Object> dataAccess = (Map<String, Object>) overview.get("dataAccess");
        assertEquals(2, intOf(dataAccess.get("total")));
        assertEquals(1, intOf(dataAccess.get("allowed")));
        assertEquals(1, intOf(dataAccess.get("denied")));
        // 拦截数 = 网关判定拒绝 + 接口服务数据访问被拒（§18.4.6 M6）
        assertEquals(2, intOf(overview.get("blocked")));

        Map<String, Object> llm = (Map<String, Object>) overview.get("llm");
        assertEquals(3, intOf(llm.get("calls")));
        assertEquals(100, intOf(llm.get("latencyP50Ms")));
        assertEquals(300, intOf(llm.get("latencyP95Ms")));
        assertEquals(0, new java.math.BigDecimal(String.valueOf(llm.get("cost"))).compareTo(new java.math.BigDecimal("0.03")));

        Map<String, Object> drift = (Map<String, Object>) overview.get("driftSignals");
        assertEquals(1, intOf(drift.get("deniedAccess")));
        assertEquals(1, intOf(drift.get("truncatedResults")));
        assertNotNull(overview.get("window"));
    }

    private void seedTraceData() {
        insert("""
                INSERT INTO data_access_audit
                    (request_id, trace_id, caller_key_id, user_id, service, http_method, http_path,
                     skill_code, scope_snapshot, outcome, reason, row_count, truncated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                REQUEST, TRACE, "key-1", "alice", "interface-doctor", "POST", "/doctor/performance", null,
                "{\"depts\":[\"心内科\"]}", "ALLOW", null, 5, false);
        insert("""
                INSERT INTO data_access_audit
                    (request_id, trace_id, caller_key_id, user_id, service, http_method, http_path,
                     skill_code, scope_snapshot, outcome, reason, row_count, truncated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                REQUEST, TRACE, "key-1", "alice", "interface-doctor", "POST", "/doctor/performance", null,
                "{\"depts\":[\"心内科\"]}", "DENY", "授权范围推导失败（fail-closed）", 0, true);
        insert("""
                INSERT INTO permission_audit
                    (trace_id, user_id, role_ids, tool_name, decision, reason, scope_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TRACE, "alice", "[2]", "iface_doctor_performance", "DENY", "无授权", null);
        for (String tool : List.of("iface_doctor_performance", "iface_doctor_list", "iface_doctor_performance")) {
            insert("""
                    INSERT INTO tool_call
                        (event_id, trace_id, turn_id, tool_name, args, result_size, status, error, latency_ms, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "evt-" + tool + "-" + now.toEpochMilli(), TRACE, "turn-1", tool, "{}", 10L, "ok", null, 20L);
        }
        insert("""
                INSERT INTO llm_call
                    (event_id, trace_id, turn_id, model, prompt_hash, tokens_in, tokens_out, cost, latency_ms, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "evt-llm-1", TRACE, "turn-1", "deepseek-flash", "hash-1", 10, 5, new java.math.BigDecimal("0.01"), 100L, "ok");
        insert("""
                INSERT INTO llm_call
                    (event_id, trace_id, turn_id, model, prompt_hash, tokens_in, tokens_out, cost, latency_ms, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "evt-llm-2", TRACE, "turn-1", "deepseek-flash", "hash-2", 20, 8, new java.math.BigDecimal("0.02"), 300L, "error");
        insert("""
                INSERT INTO llm_call
                    (event_id, trace_id, turn_id, model, prompt_hash, tokens_in, tokens_out, cost, latency_ms, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "evt-llm-3", TRACE, "turn-1", "deepseek-flash", "hash-3", 5, 2, new java.math.BigDecimal("0.00"), null, "ok");
        insert("""
                INSERT INTO conversation_turn
                    (event_id, session_id, turn_id, trace_id, user_input, final_answer, latency_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "evt-turn-1", "session-1", "turn-1", TRACE, "张医生上月绩效", "…", 900L);
        insert("""
                INSERT INTO agent_step
                    (event_id, session_id, turn_id, trace_id, seq, type, content, tool_name, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "evt-step-1", "session-1", "turn-1", TRACE, 1L, "action", null, "iface_doctor_performance");
    }

    private void insert(String sql, Object... args) {
        Object[] withTime = new Object[args.length + 1];
        System.arraycopy(args, 0, withTime, 0, args.length);
        withTime[args.length] = Timestamp.from(now);
        jdbc.update(sql, withTime);
    }

    private String iso(long offsetSeconds) {
        return now.plusSeconds(offsetSeconds).toString();
    }

    private static int intOf(Object value) {
        return ((Number) value).intValue();
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
