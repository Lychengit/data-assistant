package com.djzy.assistant.gateway;

import static com.djzy.assistant.gateway.GatewayTestSupport.ALICE;
import static com.djzy.assistant.gateway.GatewayTestSupport.PATH_EXPORT;
import static com.djzy.assistant.gateway.GatewayTestSupport.PATH_LIST;
import static com.djzy.assistant.gateway.GatewayTestSupport.PATH_PERFORMANCE;
import static com.djzy.assistant.gateway.GatewayTestSupport.ROUTE_EXPORT;
import static com.djzy.assistant.gateway.GatewayTestSupport.ROUTE_PERFORMANCE;
import static com.djzy.assistant.gateway.GatewayTestSupport.BOB;
import static com.djzy.assistant.gateway.GatewayTestSupport.CAROL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.permission.PermissionAuditEntry;
import com.djzy.assistant.common.permission.PermissionDecision;
import com.djzy.assistant.common.security.ServiceSigner;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** 网关 G1 / G3 / G2 / G4 / G5 的端到端行为（§18.4.2 / §19.7 / §20.1 / §20.12）。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.persistence=none",
            "gateway.allowed-callers.agent-service=" + GatewayTestSupport.AGENT_SECRET,
            "gateway.allowed-callers.management-service=" + GatewayTestSupport.MANAGEMENT_SECRET,
            "gateway.secrets.login-token=" + GatewayTestSupport.JWT_SECRET,
            "gateway.secrets.gateway=" + GatewayTestSupport.OUTBOUND_SECRET,
            "gateway.per-user-qps=100",
            "gateway.circuit-failure-threshold=100",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
@Import({GatewayTestSupport.Fakes.class, GatewayTestSupport.RecordingDownstream.class})
class GatewayDataControllerTest {

    private static final String CALL_PATH = "/v1/gateway/api-call";
    private static final String CAPABILITIES_PATH = "/v1/permission/capabilities";

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayTestSupport.RecordingDownstreamClient downstream;

    @Autowired
    private GatewayTestSupport.RecordingAuditWriter auditWriter;

    @Autowired
    private GatewayTestSupport.InMemoryConfirmStore confirmStore;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        downstream.clear();
        auditWriter.clear();
    }

    /**
     * §0.3-3 / §20.1.4：验签失败的审计**一条不漏**。
     *
     * <p>这条是实测过真事故后补的：不带任何签名头的探测（最典型的扫描形态）会让审计快照里出现 null，
     * 而快照用 {@code Map.copyOf} 拷贝（拒绝 null）→ 整条审计抛 NPE，只落一条 ERROR 日志，
     * {@code permission_audit} 里一行都没有。401 回得没错，错的是「谁在扫」这件事没留下痕迹。
     */
    @Test
    void 缺签名头的探测也要留下审计() {
        GatewayTestSupport.HttpResult response = GatewayTestSupport.send(
                baseUrl, "GET", CAPABILITIES_PATH, null, GatewayTestSupport.jwtFor(ALICE), Map.of());

        assertEquals(401, response.status());
        assertEquals(1, auditWriter.entries().size());
        PermissionAuditEntry entry = auditWriter.entries().get(0);
        assertEquals(PermissionDecision.DENY, entry.decision());
        assertEquals("signature-verify", entry.toolName());
        // 缺的字段如实留成 null（探测者确实没给时间戳），而不是让整条审计写不进去
        assertTrue(entry.scopeSnapshot().containsKey("timestamp"));
        assertNull(entry.scopeSnapshot().get("timestamp"));
        assertNull(entry.scopeSnapshot().get("keyId"));
    }

    @Test
    void forwardsDerivedIdentityAndArgs() {
        String body = GatewayTestSupport.callBody(
                PATH_PERFORMANCE, "req-1", null, null, Map.of("month", "2026-08"));

        GatewayTestSupport.HttpResult response =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, GatewayTestSupport.jwtFor(ALICE));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("rowCount"));
        ApiEnvelope<Map<String, Object>> call = downstream.lastCall();
        // 身份由网关推导后塞进信封，调用方说了不算（§20.1.6-5）
        assertEquals(ALICE, call.caller().userId());
        assertEquals("req-1", call.caller().requestId());
        assertEquals("2026-08", call.args().get("month"));
        assertEquals(PermissionDecision.ALLOW, auditWriter.lastFor(ROUTE_PERFORMANCE).orElseThrow().decision());
    }

    @Test
    void rejectsCallWithoutSignature() {
        String body = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-2", null, null, Map.of());

        GatewayTestSupport.HttpResult response = GatewayTestSupport.send(baseUrl, "POST", CALL_PATH, body, null, Map.of());

        assertEquals(401, response.status());
        assertTrue(response.body().contains(UnifiedErrors.UNAUTHORIZED));
        assertTrue(downstream.calls().isEmpty());
    }

    @Test
    void rejectsTamperedBody() {
        String signedBody = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-3", null, null, Map.of());
        String tamperedBody = GatewayTestSupport.callBody(PATH_LIST, "req-3", null, null, Map.of());
        Map<String, String> signature =
                ServiceSigner.sign(GatewayTestSupport.AGENT, "POST", CALL_PATH, Map.of(), signedBody);

        GatewayTestSupport.HttpResult response =
                GatewayTestSupport.send(baseUrl, "POST", CALL_PATH, tamperedBody, GatewayTestSupport.jwtFor(ALICE), signature);

        assertEquals(401, response.status());
        assertTrue(downstream.calls().isEmpty());
    }

    @Test
    void rejectsReplayedNonce() {
        String body = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-4", null, null, Map.of());
        String nonce = ServiceSigner.newNonce();
        long now = Instant.now().getEpochSecond();
        String jwt = GatewayTestSupport.jwtFor(ALICE);

        GatewayTestSupport.HttpResult first =
                GatewayTestSupport.signedPostWith(baseUrl, CALL_PATH, body, jwt, now, nonce);
        GatewayTestSupport.HttpResult second =
                GatewayTestSupport.signedPostWith(baseUrl, CALL_PATH, body, jwt, now, nonce);

        assertEquals(200, first.status());
        assertEquals(401, second.status());
        assertEquals(1, downstream.calls().size());
    }

    @Test
    void rejectsExpiredTimestamp() {
        String body = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-5", null, null, Map.of());

        GatewayTestSupport.HttpResult response = GatewayTestSupport.signedPostWith(
                baseUrl,
                CALL_PATH,
                body,
                GatewayTestSupport.jwtFor(ALICE),
                Instant.now().getEpochSecond() - 600,
                ServiceSigner.newNonce());

        assertEquals(401, response.status());
        assertTrue(downstream.calls().isEmpty());
    }

    @Test
    void deniesWhenApiNotInUnion() {
        String body = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-6", null, null, Map.of());

        GatewayTestSupport.HttpResult response =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, GatewayTestSupport.jwtFor(BOB));

        assertEquals(403, response.status());
        assertTrue(response.body().contains(UnifiedErrors.FORBIDDEN));
        assertTrue(downstream.calls().isEmpty());
        assertEquals(
                "API_NOT_IN_USER_UNION",
                auditWriter.lastFor(ROUTE_PERFORMANCE).orElseThrow().reason());
    }

    @Test
    void rejectsDisabledUser() {
        String body = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-7", null, null, Map.of());

        GatewayTestSupport.HttpResult response =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, GatewayTestSupport.jwtFor(CAROL));

        assertEquals(401, response.status());
        assertTrue(downstream.calls().isEmpty());
    }

    @Test
    void rejectsUnknownApi() {
        String body = GatewayTestSupport.callBody("/not/registered", "req-8", null, null, Map.of());

        GatewayTestSupport.HttpResult response =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, GatewayTestSupport.jwtFor(ALICE));

        assertEquals(404, response.status());
        assertTrue(downstream.calls().isEmpty());
    }

    @Test
    void writeRequiresOneShotConfirmation() {
        String withoutConfirm = GatewayTestSupport.callBody(PATH_EXPORT, "req-9", null, null, Map.of());

        GatewayTestSupport.HttpResult denied =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, withoutConfirm, GatewayTestSupport.jwtFor(BOB));

        assertEquals(403, denied.status());
        assertEquals("CONFIRM_REQUIRED", auditWriter.lastFor(ROUTE_EXPORT).orElseThrow().reason());

        confirmStore.put("c-1", BOB);
        String withConfirm = GatewayTestSupport.callBody(PATH_EXPORT, "req-10", null, "c-1", Map.of());
        GatewayTestSupport.HttpResult allowed =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, withConfirm, GatewayTestSupport.jwtFor(BOB));
        assertEquals(200, allowed.status());

        GatewayTestSupport.HttpResult replayed =
                GatewayTestSupport.signedPost(baseUrl, CALL_PATH, withConfirm, GatewayTestSupport.jwtFor(BOB));
        assertEquals(403, replayed.status());
        assertFalse(auditWriter.entries().isEmpty());
    }

    @Test
    void capabilitiesAreDerivedFromTokenOnly() {
        GatewayTestSupport.HttpResult response = GatewayTestSupport.signedGet(
                baseUrl, CAPABILITIES_PATH, GatewayTestSupport.jwtFor(ALICE));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("perf_report"));
        assertTrue(response.body().contains(PATH_PERFORMANCE));
        assertTrue(response.body().contains(PATH_LIST));
        // 入参契约必须跟着下发：agent-service 拿它当工具 schema（§4.8）。
        // 少了它模型只能猜参数名，表现为「接口看得见、却一个都调不通」。
        assertTrue(response.body().contains("\"paramSchema\""));
        assertTrue(response.body().contains("\"month\""));
        assertTrue(response.body().contains("医生绩效"));
        // 场景与返回字段契约也必须跟着下发（§4.8 节点 A）：模型靠「什么时候用」选接口、
        // 靠「有哪些字段」避免编字段名。少了这两项，接口一多就会选错、字段名写错。
        assertTrue(response.body().contains("\"scenario\""));
        assertTrue(response.body().contains("\"resultSchema\""));
        assertTrue(response.body().contains("医生排名或明细时用这个接口"));

        GatewayTestSupport.HttpResult unauthenticated =
                GatewayTestSupport.signedGet(baseUrl, CAPABILITIES_PATH, null);
        assertEquals(401, unauthenticated.status());
    }
}
