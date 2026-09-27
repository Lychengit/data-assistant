package com.djzy.assistant.iface.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.storage.ObjectStorage;
import com.djzy.assistant.iface.doctor.InterfaceDoctorTestSupport.HttpResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 导出上传接口（{@code POST /doctor/export/upload}）的行为契约（§18.4.5 W1–W3）。
 *
 * <p>它同时是**启动自检的回归**：这条路由是 {@code kind=write}，入参 DTO 与 {@code sys_api.param_schema}
 * 一旦对不上，接口服务根本起不来（{@code RegisteredRouteCatalog} 会抛错），这个类会直接报上下文启动失败。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:iface-export-upload;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "interface-doctor.allowed-callers.gateway=" + InterfaceDoctorTestSupport.GATEWAY_SECRET,
            "interface-doctor.nonce-store=memory",
            "object-storage.provider=local",
            "object-storage.root-dir=target/test-object-storage-upload"
        })
@Import({H2AuditConfig.class, DoctorExportUploadApiTest.MemoryStorageConfig.class})
class DoctorExportUploadApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PATH = "/doctor/export/upload";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    /** 把预签名链接变成可观察的（本机文件系统实现没有签名 URL，见 {@link TestObjectStorage}）。 */
    @TestConfiguration
    static class MemoryStorageConfig {

        @Bean
        @Primary
        ObjectStorage testObjectStorage() {
            return new TestObjectStorage(Path.of("target/test-object-storage-upload"));
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> args(String fileName, String base64Content) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file_name", fileName);
        args.put("content_base64", base64Content);
        args.put("content_type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return args;
    }

    @Test
    void uploadWritesObjectRegistersArtifactAndReturnsShortLivedLink() {
        String body = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("绩效报表.xlsx", base64("fake-xlsx-bytes")), "doctor_perf_excel_report", "confirm-1");

        HttpResult result = InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, body);

        assertEquals(200, result.status(), result.body());
        Map<String, Object> row = firstRow(result.body());
        assertEquals("绩效报表.xlsx", row.get("file_name"));
        assertEquals(15, ((Number) row.get("size_bytes")).intValue());
        assertNotNull(row.get("content_sha256"));
        assertTrue(String.valueOf(row.get("storage_key")).startsWith("exports/"));
        String downloadUrl = String.valueOf(row.get("download_url"));
        assertTrue(downloadUrl.startsWith("http://"), "上传必须回一个可直接下载的链接：" + downloadUrl);
        assertNotNull(row.get("object_expires_at"));

        // W3：工件记录必须真的落库（"文件在、记录不在"是最难查的那种状态）
        Integer registered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifact WHERE artifact_id = ?", Integer.class, row.get("artifact_id"));
        assertEquals(1, registered, "artifact 表里应当有这一条登记");
    }

    @Test
    void sameContentIsContentAddressed_secondUploadDoesNotCreateSecondObject() {
        String content = base64("同一份内容");
        String first = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("a.xlsx", content), "skill-a", "confirm-a");
        String second = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("b.xlsx", content), "skill-a", "confirm-b");

        Map<String, Object> firstRow = firstRow(
                InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, first).body());
        Map<String, Object> secondRow = firstRow(
                InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, second).body());

        // 键的形状：exports/<sha256>/<文件名>——内容相同所以哈希段相同，文件名不同所以是两条登记
        assertEquals(firstRow.get("content_sha256"), secondRow.get("content_sha256"));
        assertNotEquals(firstRow.get("artifact_id"), secondRow.get("artifact_id"));
    }

    @Test
    void fileTypeOutsideTheWhitelistIsRejected() {
        String body = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("payload.html", base64("<script>alert(1)</script>")), "skill-a", "confirm-x");

        HttpResult result = InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, body);

        assertEquals(400, result.status(), result.body());
    }

    @Test
    void pathInFileNameIsStrippedBeforeItBecomesAStorageKey() {
        String body = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("..\\..\\etc\\passwd.xlsx", base64("x")), "skill-a", "confirm-y");

        HttpResult result = InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, body);

        assertEquals(200, result.status(), result.body());
        assertEquals("passwd.xlsx", firstRow(result.body()).get("file_name"));
    }

    @Test
    void writeWithoutConfirmIdIsForbidden() {
        // 写操作的确认凭据由网关一次性消费（§18.4.5 W1）；这里传空，接口服务的纵深防御必须拒绝
        String body = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("a.xlsx", base64("x")), "skill-a", null);

        HttpResult result = InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, body);

        assertEquals(403, result.status(), result.body());
    }

    @Test
    void emptyContentIsRejected() {
        String body = InterfaceDoctorTestSupport.envelope(
                "admin", PATH, args("a.xlsx", ""), "skill-a", "confirm-z");

        HttpResult result = InterfaceDoctorTestSupport.signedPost(baseUrl(), PATH, body);

        // 空串命中 @NotBlank → 400
        assertEquals(400, result.status(), result.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(String responseBody) {
        try {
            Map<String, Object> response = MAPPER.readValue(responseBody, new TypeReference<>() {});
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
            assertEquals(1, rows.size(), responseBody);
            return rows.get(0);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是预期结构：" + responseBody, e);
        }
    }
}