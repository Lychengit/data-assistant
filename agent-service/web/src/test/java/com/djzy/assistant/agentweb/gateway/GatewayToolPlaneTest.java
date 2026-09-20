package com.djzy.assistant.agentweb.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.SignatureHeaders;
import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvocationStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 工具面 ↔ 网关的线格式（§19.7 / §20.1.3）：用桩服务当「网关」，验证我们发出去的确实是
 * **带签名的、不含身份字段的**请求，且应答被映射成统一的工具结果。
 */
class GatewayToolPlaneTest {

    private static final String KEY_ID = "agent-service";
    private static final String SECRET = "test-agent-secret";

    private HttpServer server;
    private GatewayToolPlane plane;
    private final List<HttpExchange> received = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int capabilitiesStatus = 200;
    /** 能力清单原文：默认带契约明细；个别用例会换成「接口没录入参契约」「条目缺三元组」的样子。 */
    private volatile String capabilitiesBody =
            "{\"userId\":\"alice\",\"viewableSkills\":[],"
                    + "\"apis\":[{\"service\":\"interface-doctor\",\"httpMethod\":\"POST\",\"httpPath\":\"/doctor/performance\","
                    + "\"name\":\"医生绩效明细\",\"kind\":\"read\",\"resource\":\"doctor\","
                    + "\"paramSchema\":{\"month\":{\"type\":\"string\"},\"metric_key\":{\"type\":\"string\"}},"
                    + "\"scenario\":\"查某月的门诊量或药占比时用它\","
                    + "\"resultSchema\":{\"metric_value\":{\"type\":\"number\",\"description\":\"指标值，药占比是 0~1 的小数\"}}}],"
                    + "\"computedAt\":\"2026-09-19T00:00:00Z\"}";
    private volatile int callStatus = 200;
    private volatile String callBody =
            "{\"service\":\"interface-doctor\",\"httpMethod\":\"POST\",\"httpPath\":\"/doctor/performance\","
                    + "\"columns\":[\"doctor_id\",\"metric_value\"],"
                    + "\"rows\":[{\"doctor_id\":\"1\",\"metric_value\":128}],\"truncated\":false,"
                    + "\"provenance\":{\"metric\":\"outpatient_visits\"}}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/permission/capabilities", exchange -> respond(exchange, capabilitiesStatus, capabilitiesBody));
        server.createContext("/v1/gateway/api-call", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, callStatus, callBody);
        });
        server.start();
        GatewayClient client = new GatewayClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ServiceCredential(KEY_ID, SECRET),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        plane = new GatewayToolPlane(client);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 可见接口被翻译成工具且请求已签名() {
        var catalog = plane.catalogFor("user-token");

        assertThat(catalog.all()).singleElement().satisfies(spec -> {
            assertThat(spec.name()).isEqualTo("iface_doctor_performance");
            assertThat(spec.category().name()).isEqualTo("IFACE");
            // 工具 schema 必须带上真实参数名：没有它模型只能猜参数名，每次调用都被接口服务 I3 拒掉
            assertThat(spec.inputSchema()).containsEntry("type", "object");
            assertThat(spec.inputSchema())
                    .containsEntry(
                            "properties",
                            Map.of("month", Map.of("type", "string"), "metric_key", Map.of("type", "string")));
            assertThat(spec.description()).contains("month").contains("metric_key");
        // 描述要同时回答三件事：什么时候用（选择）、怎么填（入参逐条展开）、能拿到什么（返回字段）
        assertThat(spec.description()).contains("适用场景：查某月的门诊量或药占比时用它");
        assertThat(spec.description()).contains("入参：");
        assertThat(spec.description()).contains("返回字段");
        assertThat(spec.description()).contains("metric_value（number）：指标值，药占比是 0~1 的小数");
        });
        HttpExchange exchange = received.get(0);
        assertThat(exchange.getRequestHeaders().getFirst(SignatureHeaders.API_KEY)).isEqualTo(KEY_ID);
        assertThat(exchange.getRequestHeaders().getFirst(SignatureHeaders.SIGNATURE)).startsWith("v1:");
        assertThat(exchange.getRequestHeaders().getFirst(SignatureHeaders.NONCE)).isNotBlank();
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer user-token");
    }

    @Test
    void 调用走网关且请求体不含身份与范围() {
        ToolInvocationResult result = reactor.core.publisher.Mono.from(plane.invokerFor("user-token")
                        .invoke(new ToolInvocationRequest(
                        "iface_doctor_performance",
                        Map.of("month", "2026-08"),
                        "alice",
                        "s1",
                        "t1",
                        "trace-1",
                        "req-1",
                        null,
                        System.currentTimeMillis() + 5_000,
                        Map.of())))
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ToolInvocationStatus.OK);
        assertThat(result.meta()).containsEntry("rowCount", 1);
        assertThat(bodies).hasSize(1);
        // 请求体只带身份三元组与入参：不给 userId / 角色 / 范围留位置（§20.1.6-5）
        assertThat(bodies.get(0))
                .contains("\"service\":\"interface-doctor\"")
                .contains("\"httpMethod\":\"POST\"")
                .contains("\"httpPath\":\"/doctor/performance\"")
                .doesNotContain("userId")
                .doesNotContain("roles")
                .doesNotContain("scope");
    }

    @Test
    void 网关拒绝时统一成拒绝结果() {
        callStatus = 403;
        callBody = "{\"error\":\"未找到您有权查看的相关数据\"}";

        ToolInvocationResult result = reactor.core.publisher.Mono.from(plane.invokerFor("user-token")
                        .invoke(new ToolInvocationRequest(
                                "iface_doctor_performance", Map.of(), "alice", "s1", "t1", "tr", "req", null, 0, Map.of())))
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ToolInvocationStatus.DENIED);
        assertThat(result.content()).isEqualTo("未找到您有权查看的相关数据");
    }

    /** M4 管理端按完整 JSON Schema 录入时原样透传：required 是模型最需要、扁平表又给不了的信息。 */
    @Test
    void 完整JSON_Schema口径原样透传() {
        capabilitiesBody =
                "{\"userId\":\"alice\",\"apis\":[{"
                        + "\"service\":\"interface-doctor\",\"httpMethod\":\"POST\",\"httpPath\":\"/doctor/performance\","
                        + "\"name\":\"医生绩效明细\",\"kind\":\"read\",\"paramSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"month\":{\"type\":\"string\"}},\"required\":[\"month\"],"
                        + "\"additionalProperties\":false}}]}";

        var catalog = plane.catalogFor("user-token");

        Map<String, Object> schema = catalog.all().get(0).inputSchema();
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat(schema.get("required")).isEqualTo(List.of("month"));
        assertThat(catalog.all().get(0).description()).contains("month（必填，string）");
    }

    /**
     * 没有场景与返回契约时（老库 / 还没补录的接口）描述要退化成简版，而不是多出空标题。
     */
    @Test
    void 缺少场景与返回契约时描述退化但不留空标题() {
        capabilitiesBody =
                "{\"userId\":\"alice\",\"apis\":[{"
                        + "\"service\":\"interface-doctor\",\"httpMethod\":\"POST\",\"httpPath\":\"/doctor/performance\","
                        + "\"name\":\"医生绩效明细\",\"kind\":\"read\","
                        + "\"paramSchema\":{\"month\":{\"type\":\"string\"}}}]}";

        String description = plane.catalogFor("user-token").all().get(0).description();

        assertThat(description).contains("调用医生数据接口 iface_doctor_performance");
        assertThat(description).contains("month（可选，string）");
        assertThat(description).doesNotContain("适用场景").doesNotContain("返回字段");
    }

    /** 接口还没录入参契约时工具仍要建得出来：宽 schema 是降级路径，不是「没有工具」。 */
    @Test
    void 没录入参契约时退化为宽松schema() {
        capabilitiesBody =
                "{\"userId\":\"alice\",\"apis\":[{"
                        + "\"service\":\"interface-doctor\",\"httpMethod\":\"POST\",\"httpPath\":\"/doctor/performance\","
                        + "\"name\":\"医生绩效明细\",\"kind\":\"read\"}]}";

        var catalog = plane.catalogFor("user-token");

        assertThat(catalog.all()).singleElement().satisfies(spec -> {
            assertThat(spec.name()).isEqualTo("iface_doctor_performance");
            assertThat(spec.inputSchema()).containsEntry("additionalProperties", true);
        });
    }

    /**
     * 条目缺三元组的接口**不进工具面**：网关按「服务 + 方法 + 路径」查注册行，缺一项就查不到，
     * 模型拿到这种工具只会得到一个必然失败的调用。宁可它看不见，也不要让它看见一个注定调不通的工具。
     */
    @Test
    void 缺三元组的条目不进工具面() {
        capabilitiesBody =
                "{\"userId\":\"alice\",\"apis\":[{\"name\":\"没有路由的接口\",\"kind\":\"read\","
                        + "\"paramSchema\":{\"month\":{\"type\":\"string\"}}}]}";

        assertThat(plane.catalogFor("user-token").all()).isEmpty();

    }
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        received.add(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (InputStream ignored = exchange.getRequestBody()) {
            // 先把请求体会员读完，否则响应可能被重置
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
