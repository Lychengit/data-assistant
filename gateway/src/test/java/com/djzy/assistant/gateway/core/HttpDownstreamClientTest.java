package com.djzy.assistant.gateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.ApiKind;
import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.security.InMemoryNonceStore;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceVerifier;
import com.djzy.assistant.common.security.StaticSecretResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 网关 → 接口服务 的签名与重试（§18.4.2 G2 / §20.1）：重试必须重新签名、换 nonce。 */
class HttpDownstreamClientTest {

    private static final String SECRET = "gateway-to-interface-secret";
    private static final String PATH = "/doctor/performance";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsEnvelopeThatInterfaceServiceCanVerify() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<Boolean> verified = new AtomicReference<>(false);
        ServiceVerifier verifier = new ServiceVerifier(
                StaticSecretResolver.of(Map.of("gateway", SECRET)), new InMemoryNonceStore());
        server = startServer(exchange -> {
            String received = readBody(exchange);
            body.set(received);
            boolean ok = verifier
                    .verify(
                            headersOf(exchange),
                            "POST",
                            exchange.getRequestURI().getPath(),
                            Map.of(),
                            received,
                            Instant.now().getEpochSecond())
                    .verified();
            verified.set(ok);
            respond(exchange, ok ? 200 : 401, ok ? "{\"rows\":[]}" : "{\"error\":\"unauthorized\"}");
        });

        DownstreamResponse response = client().send(envelope(), descriptor());

        assertEquals(200, response.status());
        assertEquals(true, verified.get());
        // 信封里是「可信身份 + 业务入参」两段，身份由网关推导、不经调用方（§20.1.6-5）
        assertTrue(body.get().contains("\"userId\":\"alice\""));
        assertTrue(body.get().contains("\"month\":\"2026-08\""));
        // 范围不再下发（它由接口服务基于登录人自己推导，§19.1）——信封里不该出现它
        assertFalse(body.get().contains("\"scope\""));
    }

    @Test
    void retriesWithFreshNonceOnServerError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstNonce = new AtomicReference<>();
        AtomicReference<String> secondNonce = new AtomicReference<>();
        server = startServer(exchange -> {
            int call = calls.incrementAndGet();
            String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
            if (call == 1) {
                firstNonce.set(nonce);
                readBody(exchange);
                respond(exchange, 500, "{\"error\":\"boom\"}");
            } else {
                secondNonce.set(nonce);
                readBody(exchange);
                respond(exchange, 200, "{\"rows\":[]}");
            }
        });

        DownstreamResponse response = client().send(envelope(), descriptor());

        assertEquals(200, response.status());
        assertEquals(2, calls.get());
        assertNotEquals(firstNonce.get(), secondNonce.get());
    }

    private HttpDownstreamClient client() {
        return new HttpDownstreamClient(
                new ServiceRouter("http://localhost:" + server.getAddress().getPort()),
                new ServiceCredential("gateway", SECRET),
                HttpClient.newHttpClient(),
                1,
                Duration.ofSeconds(3),
                Duration.ofSeconds(3));
    }

    /** 允许抛 IOException 的处理器（com.sun.net.httpserver 的 Consumer 形态不允许）。 */
    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(PATH, exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> headersOf(HttpExchange exchange) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
        return headers;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ApiEnvelope<Map<String, Object>> envelope() {
        return new ApiEnvelope<>(
                new CallerInfo("alice", "req-1", "trace-1", null, null), Map.of("month", "2026-08"));
    }

    private static ApiDescriptor descriptor() {
        return new ApiDescriptor(
                1L, "医生绩效", "interface-doctor", "POST", PATH, ApiKind.READ, "doctor", Map.of(), true, null, Map.of());
    }
}