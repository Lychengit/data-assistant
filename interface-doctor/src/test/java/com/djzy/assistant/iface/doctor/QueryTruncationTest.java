package com.djzy.assistant.iface.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * 行数上限（§4.8）：超限即截断并**显式标注**，不悄悄少给数据；多查一行用于判断是否真的被截断。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:iface-trunc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "interface-doctor.allowed-callers.gateway=" + InterfaceDoctorTestSupport.GATEWAY_SECRET,
            "interface-doctor.nonce-store=memory",
            "interface-doctor.max-rows=2"
        })
@Import(H2AuditConfig.class)
class QueryTruncationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void oversizedResultIsTruncatedAndFlagged() {
        String body = InterfaceDoctorTestSupport.envelope("alice", InterfaceDoctorTestSupport.PATH_LIST, Map.of());
        HttpResult result =
                InterfaceDoctorTestSupport.signedPost("http://localhost:" + port, InterfaceDoctorTestSupport.PATH_LIST, body);

        assertEquals(200, result.status(), result.body());
        Map<String, Object> response = read(result.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
        assertEquals(2, rows.size());
        assertTrue((Boolean) response.get("truncated"), "超限必须显式标注，不能悄悄少给");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT service, http_path, row_count, truncated FROM data_access_audit"
                        + " WHERE outcome = 'ALLOW' ORDER BY id DESC LIMIT 1");
        assertEquals("interface-doctor", audit.get("service"));
        assertEquals(InterfaceDoctorTestSupport.PATH_LIST, audit.get("http_path"));
        assertEquals(2, audit.get("row_count"));
        assertEquals(true, audit.get("truncated"));
    }

    private static Map<String, Object> read(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON：" + json, e);
        }
    }
}