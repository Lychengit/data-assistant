package com.djzy.assistant.iface.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceSigner;
import com.djzy.assistant.iface.doctor.InterfaceDoctorTestSupport.HttpResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * I1 验签（§20.1.4）：缺签名 / 密钥不认识 / 改包 / 超窗 / 重放一律 401，且对外只有统一措辞。
 *
 * <p>接口服务是「验签但不判定」：这些用例证明**绕过网关直连或篡改下发内容都进不来**（§19.13 例 5）。
 *
 * <p>路径用真实业务路径（{@code /doctor/*}）：验签范围跟着 {@code interface-doctor.api-prefix} 走，
 * 加了接口却忘了把它放进保护范围，就是这里红。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:iface-sig;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:schema-h2.sql",
            "interface-doctor.allowed-callers.gateway=" + InterfaceDoctorTestSupport.GATEWAY_SECRET,
            "interface-doctor.nonce-store=memory"
        })
class InboundSignatureTest {

    private static final String PATH = InterfaceDoctorTestSupport.PATH_LIST;

    @LocalServerPort
    int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String body() {
        return InterfaceDoctorTestSupport.envelope("alice", PATH, Map.of("dept_code", "心内科"));
    }

    private static void assertUnauthorized(HttpResult result) {
        assertEquals(401, result.status(), result.body());
        assertEquals("{\"error\":\"" + UnifiedErrors.UNAUTHORIZED + "\"}", result.body());
    }

    @Test
    void missingSignatureIsUnauthorized() {
        assertUnauthorized(InterfaceDoctorTestSupport.post(baseUrl(), PATH, body(), Map.of()));
    }

    @Test
    void unknownKeyIdIsUnauthorized() {
        ServiceCredential rogue = new ServiceCredential("rogue-service", "some-other-secret");
        String payload = body();
        HttpResult result = InterfaceDoctorTestSupport.post(
                baseUrl(), PATH, payload, ServiceSigner.sign(rogue, "POST", PATH, Map.of(), payload));

        assertUnauthorized(result);
    }

    @Test
    void tamperedBodyIsUnauthorized() {
        String signed = body();
        Map<String, String> headers = ServiceSigner.sign(
                InterfaceDoctorTestSupport.GATEWAY, "POST", PATH, Map.of(), signed);
        // 篡改下发的身份/入参（把调用人换成别人）→ 请求体摘要变化 → 验签失败
        String tampered = signed.replace("alice", "bob");

        assertUnauthorized(InterfaceDoctorTestSupport.post(baseUrl(), PATH, tampered, headers));
    }

    @Test
    void replayedNonceIsUnauthorized() {
        String payload = body();
        Map<String, String> headers = ServiceSigner.sign(
                InterfaceDoctorTestSupport.GATEWAY,
                "POST",
                PATH,
                Map.of(),
                payload,
                Instant.now().getEpochSecond(),
                "replay-nonce-0001");

        assertEquals(200, InterfaceDoctorTestSupport.post(baseUrl(), PATH, payload, headers).status());
        assertUnauthorized(InterfaceDoctorTestSupport.post(baseUrl(), PATH, payload, headers));
    }

    @Test
    void timestampOutOfWindowIsUnauthorized() {
        long stale = Instant.now().getEpochSecond() - 400;
        long future = Instant.now().getEpochSecond() + 400;

        assertUnauthorized(InterfaceDoctorTestSupport.signedPostWith(
                baseUrl(), PATH, body(), InterfaceDoctorTestSupport.GATEWAY, stale, "stale-nonce-0001"));
        assertUnauthorized(InterfaceDoctorTestSupport.signedPostWith(
                baseUrl(), PATH, body(), InterfaceDoctorTestSupport.GATEWAY, future, "future-nonce-0001"));
    }

    /** 签名覆盖路径：给 A 接口签的名不能用来调 B 接口（§20.1.3）。 */
    @Test
    void signatureIsBoundToPath() {
        String payload = body();
        Map<String, String> headers = ServiceSigner.sign(
                InterfaceDoctorTestSupport.GATEWAY, "POST", PATH, Map.of(), payload);

        assertUnauthorized(InterfaceDoctorTestSupport.post(
                baseUrl(), InterfaceDoctorTestSupport.PATH_PERFORMANCE, payload, headers));
    }

    /**
     * 未知路径也在保护范围内：没有签名的探测到这里仍然是 401，而不是 404。
     *
     * <p>这条守的是验签**范围**（{@code interface-doctor.api-prefix}）而不是注册表：
     * 如果换成"只保护登记过的路径"，未登记路径会先暴露 404、被人拿去扫端点是否存在。
     */
    @Test
    void unregisteredPathUnderPrefixStillRequiresSignature() {
        assertUnauthorized(InterfaceDoctorTestSupport.post(baseUrl(), "/doctor/nope", body(), Map.of()));
    }
}