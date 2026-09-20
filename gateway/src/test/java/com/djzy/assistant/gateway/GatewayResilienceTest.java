package com.djzy.assistant.gateway;

import static com.djzy.assistant.gateway.GatewayTestSupport.ALICE;
import static com.djzy.assistant.gateway.GatewayTestSupport.PATH_PERFORMANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.gateway.core.DownstreamClient;
import com.djzy.assistant.gateway.core.DownstreamResponse;
import com.djzy.assistant.gateway.core.DownstreamUnavailableException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** G4 熔断（§18.4.2 / §9.2）：连续失败即暂时切断，避免一个坏接口拖垮整条链路。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.persistence=none",
            "gateway.allowed-callers.agent-service=" + GatewayTestSupport.AGENT_SECRET,
            "gateway.allowed-callers.management-service=" + GatewayTestSupport.MANAGEMENT_SECRET,
            "gateway.secrets.login-token=" + GatewayTestSupport.JWT_SECRET,
            "gateway.secrets.gateway=" + GatewayTestSupport.OUTBOUND_SECRET,
            "gateway.per-user-qps=100",
            "gateway.circuit-failure-threshold=2",
            "gateway.circuit-open-duration=1m",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
@Import({GatewayTestSupport.Fakes.class, GatewayResilienceTest.FailingDownstream.class})
class GatewayResilienceTest {

    private static final String CALL_PATH = "/v1/gateway/api-call";

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayResilienceTest.CountingFailingClient downstream;

    @BeforeEach
    void setUp() {
        downstream.reset();
    }

    @Test
    void opensCircuitAfterConsecutiveFailures() {
        String body = GatewayTestSupport.callBody(PATH_PERFORMANCE, "req-circuit", null, null, Map.of());
        String jwt = GatewayTestSupport.jwtFor(ALICE);
        String baseUrl = "http://localhost:" + port;

        GatewayTestSupport.HttpResult first = GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, jwt);
        GatewayTestSupport.HttpResult second = GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, jwt);
        GatewayTestSupport.HttpResult third = GatewayTestSupport.signedPost(baseUrl, CALL_PATH, body, jwt);

        assertEquals(502, first.status());
        assertEquals(502, second.status());
        assertEquals(503, third.status());
        assertTrue(third.body().contains(UnifiedErrors.SERVICE_UNAVAILABLE));
        assertEquals(2, downstream.calls());
    }

    @TestConfiguration
    public static class FailingDownstream {

        @Bean
        @Primary
        public CountingFailingClient countingFailingClient() {
            return new CountingFailingClient();
        }
    }

    public static final class CountingFailingClient implements DownstreamClient {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public DownstreamResponse send(ApiEnvelope<Map<String, Object>> envelope, ApiDescriptor descriptor) {
            calls.incrementAndGet();
            throw new DownstreamUnavailableException(false, "stub downstream failure", null);
        }

        public int calls() {
            return calls.get();
        }

        public void reset() {
            calls.set(0);
        }
    }
}
