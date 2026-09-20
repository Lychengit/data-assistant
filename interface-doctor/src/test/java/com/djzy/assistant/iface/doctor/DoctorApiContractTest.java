package com.djzy.assistant.iface.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.iface.doctor.InterfaceDoctorTestSupport.HttpResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 一个接口一个端点之后的行为契约（§18.4.5 I3–I5 / §4.7）：
 * 路径就是身份、入参字段由代码写死、返回字段由各自 SQL 写死、审计由切面统一记。
 *
 * <p>这些用例同时也是**启动自检的回归测试**：接口与 {@code sys_api} 的路径、字段、必填约束
 * 一旦对不上，接口服务根本起不来（{@code RegisteredRouteCatalog} 抛错），这些测试会直接报上下文启动失败。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:iface-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "interface-doctor.allowed-callers.gateway=" + InterfaceDoctorTestSupport.GATEWAY_SECRET,
            "interface-doctor.nonce-store=memory"
        })
@Import(H2AuditConfig.class)
class DoctorApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void performanceReturnsOnlyDeclaredColumnsWithProvenance() {
        String body = InterfaceDoctorTestSupport.envelope(
                "alice",
                InterfaceDoctorTestSupport.PATH_PERFORMANCE,
                Map.of("month", "2026-08", "metric_key", "outpatient_visits", "dept_code", "心内科"));

        HttpResult result =
                InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, body);

        assertEquals(200, result.status(), result.body());
        Map<String, Object> response = read(result.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
        assertEquals(2, rows.size());
        // 返回字段就是 SQL 里显式列出的那几个（没有 SELECT *，也就没有"多返回一列"这种可能）
        assertEquals(List.of("doctor_id", "doctor_name", "dept_code", "stat_month", "metric_key", "metric_value"),
                List.copyOf(rows.get(0).keySet()));
        // 溯源标注（§6.5）：单位与口径必须跟着数据一起回去，而不是让模型去猜
        @SuppressWarnings("unchecked")
        Map<String, Object> provenance = (Map<String, Object>) response.get("provenance");
        assertEquals("interface-doctor", provenance.get("service"));
        assertEquals(InterfaceDoctorTestSupport.PATH_PERFORMANCE, provenance.get("httpPath"));
        assertEquals("outpatient_visits", provenance.get("metricKey"));
        assertEquals("人次", provenance.get("unit"));
    }

    @Test
    void metricKeyMustHitTheDictionary() {
        String body = InterfaceDoctorTestSupport.envelope(
                "alice",
                InterfaceDoctorTestSupport.PATH_PERFORMANCE,
                Map.of("month", "2026-08", "metric_key", "自己编的指标"));

        HttpResult result =
                InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, body);

        assertEquals(400, result.status(), result.body());
        assertTrue(result.body().contains(UnifiedErrors.INVALID_REQUEST));
        // 失败也要留痕（切面的 error 分支），否则"谁试了不存在的指标"查不到
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT outcome, http_path FROM data_access_audit ORDER BY id DESC LIMIT 1");
        assertEquals("ERROR", audit.get("outcome"));
        assertEquals(InterfaceDoctorTestSupport.PATH_PERFORMANCE, audit.get("http_path"));
    }

    /**
     * 月份必须是真实存在的月份，不是「四位数字-两位数字」。
     *
     * <p>实测缺口：`2026-13` 以前能一路走到 SQL，比不到任何行，于是回 200 + 空结果——
     * 对模型来说这和「这个月确实没数据」完全一样，它会照实汇报，而正确的行为是当场拒绝。
     * 空结果必须只表示「查询成立但范围内没有数据」，不能兼职表示「参数本身是错的」。
     */
    @Test
    void impossibleMonthIsRejectedInsteadOfReturningEmpty() {
        for (String month : List.of("2026-13", "2026-00", "2026-99")) {
            String body = InterfaceDoctorTestSupport.envelope(
                    "alice",
                    InterfaceDoctorTestSupport.PATH_PERFORMANCE,
                    Map.of("month", month, "metric_key", "outpatient_visits"));

            HttpResult result =
                    InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, body);

            assertEquals(400, result.status(), month + " 应该被拒：" + result.body());
            assertTrue(result.body().contains(UnifiedErrors.INVALID_REQUEST), result.body());
        }
    }

    @Test
    void missingRequiredArgumentIsRejected() {
        String body = InterfaceDoctorTestSupport.envelope(
                "alice", InterfaceDoctorTestSupport.PATH_PERFORMANCE, Map.of("metric_key", "outpatient_visits"));

        HttpResult result =
                InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, body);

        assertEquals(400, result.status(), result.body());
        assertTrue(result.body().contains(UnifiedErrors.INVALID_REQUEST));
    }

    /**
     * 参数白名单来自代码：DTO 里没有的字段一律 400。
     *
     * <p>这条以前靠管理端的一份"列白名单/参数清单"兜底，现在靠 {@code fail-on-unknown-properties}
     * 加启动时的 DTO↔schema 对账——两者都由代码本身决定，不存在"配置说能传、代码不认"的中间态。
     */
    @Test
    void undeclaredArgumentIsRejected() {
        String body = InterfaceDoctorTestSupport.envelope(
                "alice",
                InterfaceDoctorTestSupport.PATH_PERFORMANCE,
                Map.of("month", "2026-08", "metric_key", "outpatient_visits", "dept", "心内科"));

        HttpResult result =
                InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, body);

        assertEquals(403, result.status(), result.body());
        assertTrue(result.body().contains(UnifiedErrors.FORBIDDEN));
    }

    /**
     * 网关那一侧**真实序列化**出来的信封，接口服务必须收得下。
     *
     * <p>这条守的是两侧之间的缝：{@code ApiEnvelope} / {@code CallerInfo} 是"网关写、接口服务读"的线上格式，
     * 而接口服务开了 {@code fail-on-unknown-properties}——身份块也不放宽（多认一个字段就等于多一条影响可信身份的路）。
     * 于是 record 上任何 {@code getXxx()} / {@code isXxx()} 形状的派生方法都会被 Jackson 当成属性写进信封，
     * 让每次调用在网关"放行"（审计 ALLOW）之后被接口服务整单拒收：两头看日志都对，只有模型拿到拒绝。
     * 这类漂移在两侧各自的单测里都是绿的，只有把网关的序列化结果喂给本服务才照得出来。
     */
    @Test
    void gatewaySerializedEnvelopeIsAccepted() {
        CallerInfo caller = new CallerInfo("alice", "req-wire-1", "trace-wire-1", "skill-doctor-performance", null);
        String body = InterfaceDoctorTestSupport.wireEnvelope(
                caller, Map.of("month", "2026-08", "metric_key", "outpatient_visits"));

        HttpResult result =
                InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, body);

        assertEquals(200, result.status(), result.body());
        Map<String, Object> response = read(result.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
        assertTrue(rows != null && !rows.isEmpty(), result.body());
    }

    @Test
    void listReturnsDoctorRosterFilteredByDept() {
        String body = InterfaceDoctorTestSupport.envelope(
                "alice", InterfaceDoctorTestSupport.PATH_LIST, Map.of("dept_code", "呼吸科"));

        HttpResult result = InterfaceDoctorTestSupport.signedPost(baseUrl(), InterfaceDoctorTestSupport.PATH_LIST, body);

        assertEquals(200, result.status(), result.body());
        Map<String, Object> response = read(result.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
        assertEquals(2, rows.size());
        assertEquals(List.of("doctor_id", "doctor_name", "dept_code"), List.copyOf(rows.get(0).keySet()));

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT outcome, user_id, service, http_path, row_count FROM data_access_audit"
                        + " ORDER BY id DESC LIMIT 1");
        assertEquals("ALLOW", audit.get("outcome"));
        assertEquals("alice", audit.get("user_id"));
        assertEquals("interface-doctor", audit.get("service"));
        assertEquals(InterfaceDoctorTestSupport.PATH_LIST, audit.get("http_path"));
        assertEquals(2, audit.get("row_count"));
    }

    private static Map<String, Object> read(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON：" + json, e);
        }
    }
}