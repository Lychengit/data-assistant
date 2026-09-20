package com.djzy.assistant.gateway;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.ApiKind;
import com.djzy.assistant.common.api.ApiRegistry;
import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.common.api.InternalCallCodec;
import com.djzy.assistant.common.confirm.ConfirmRecord;
import com.djzy.assistant.common.confirm.ConfirmStatus;
import com.djzy.assistant.common.confirm.ConfirmStore;
import com.djzy.assistant.common.identity.HmacJwt;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.permission.PermissionAuditEntry;
import com.djzy.assistant.common.permission.PermissionAuditWriter;
import com.djzy.assistant.common.permission.PermissionRepository;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceSigner;
import com.djzy.assistant.gateway.core.DownstreamClient;
import com.djzy.assistant.gateway.core.DownstreamResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 网关测试基座：内存端口替身 + 原始 HTTP 调用助手。
 *
 * <p>用 JDK {@link HttpClient} 而非 RestTemplate：验签要求「客户端签名的字节」与「服务端收到的字节」
 * 完全一致，RestTemplate 的消息转换器会把 String 重新序列化，测不出真实链路。
 *
 * <p>接口身份是**三元组**（{@link ApiRoute}）：测试里用 {@link #SERVICE} + 路径常量 + 固定的注册行 id。
 */
public final class GatewayTestSupport {

    public static final String AGENT_SECRET = "agent-service-test-secret";
    public static final String MANAGEMENT_SECRET = "management-service-test-secret";
    public static final String JWT_SECRET = "jwt-test-secret";
    public static final String OUTBOUND_SECRET = "outbound-test-secret";
    public static final ServiceCredential AGENT = new ServiceCredential("agent-service", AGENT_SECRET);

    /** 接口服务的服务名（{@code sys_api.service}）。 */
    public static final String SERVICE = "interface-doctor";

    public static final String PATH_PERFORMANCE = "/doctor/performance";
    public static final String PATH_LIST = "/doctor/list";
    public static final String PATH_EXPORT = "/report/export";

    /** 审计里的接口标识（{@code 方法 + 路径}），与 {@code permission_audit.tool_name} 取值一致。 */
    public static final String ROUTE_PERFORMANCE = "POST " + PATH_PERFORMANCE;
    public static final String ROUTE_LIST = "POST " + PATH_LIST;
    public static final String ROUTE_EXPORT = "POST " + PATH_EXPORT;

    /** 注册行 id（授权比对的键，{@code sys_api.id}）。 */
    public static final long ID_PERFORMANCE = 1L;
    public static final long ID_LIST = 2L;
    public static final long ID_EXPORT = 3L;

    public static final String ALICE = "alice";
    public static final String BOB = "bob";
    public static final String CAROL = "carol";

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private GatewayTestSupport() {}

    /** HTTP 结果（状态码 + 响应体），避免 RestTemplate 对非 2xx 抛异常。 */
    public record HttpResult(int status, String body) {}

    public static String jwtFor(String userId) {
        return HmacJwt.issue(JWT_SECRET, userId, Duration.ofMinutes(10), Map.of());
    }

    public static HttpResult signedPost(String baseUrl, String path, String body, String jwt) {
        return send(baseUrl, "POST", path, body, jwt, ServiceSigner.sign(AGENT, "POST", path, Map.of(), body));
    }

    /** 用指定的时间戳与 nonce 签名（重放 / 过期用例）。 */
    public static HttpResult signedPostWith(
            String baseUrl, String path, String body, String jwt, long timestampSeconds, String nonce) {
        return send(
                baseUrl,
                "POST",
                path,
                body,
                jwt,
                ServiceSigner.sign(AGENT, "POST", path, Map.of(), body, timestampSeconds, nonce));
    }

    public static HttpResult signedGet(String baseUrl, String path, String jwt) {
        return send(baseUrl, "GET", path, null, jwt, ServiceSigner.sign(AGENT, "GET", path, Map.of(), ""));
    }

    public static HttpResult send(
            String baseUrl, String method, String path, String body, String jwt, Map<String, String> signature) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        signature.forEach(builder::header);
        if (jwt != null) {
            builder.header("Authorization", "Bearer " + jwt);
        }
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response =
                    HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("测试请求失败", e);
        }
    }

    /** 组装网关数据调用请求体（agent-service 只交接口身份三元组与入参，不交身份与范围）。 */
    public static String callBody(
            String httpPath, String requestId, String skillCode, String confirmId, Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", SERVICE);
        payload.put("httpMethod", "POST");
        payload.put("httpPath", httpPath);
        payload.put("requestId", requestId);
        payload.put("skillCode", skillCode);
        payload.put("confirmId", confirmId);
        payload.put("args", args);
        return InternalCallCodec.toJson(payload);
    }

    /** 内存端口替身（网关测试不连 PG）。 */
    @TestConfiguration
    public static class Fakes {

        @Bean
        @Primary
        public PermissionRepository fakePermissionRepository() {
            return standardPermissionRepository();
        }

        @Bean
        @Primary
        public ApiRegistry fakeApiRegistry() {
            return standardApiRegistry();
        }

        @Bean
        @Primary
        public UserStatusPort fakeUserStatusPort() {
            return userId -> ALICE.equals(userId) || BOB.equals(userId);
        }

        @Bean
        @Primary
        public RecordingAuditWriter fakeAuditWriter() {
            return new RecordingAuditWriter();
        }

        @Bean
        @Primary
        public InMemoryConfirmStore fakeConfirmStore() {
            return new InMemoryConfirmStore();
        }
    }

    public static ApiRegistry standardApiRegistry() {
        List<ApiDescriptor> apis = List.of(
                new ApiDescriptor(
                        ID_PERFORMANCE,
                        "医生绩效",
                        SERVICE,
                        "POST",
                        PATH_PERFORMANCE,
                        ApiKind.READ,
                        "doctor",
                        Map.of("month", "string"),
                        true,
                        "查某月的门诊量/药占比、医生排名或明细时用这个接口；问「有哪些医生」不要用它",
                        Map.of("metric_value", Map.of("type", "number", "description", "指标值"))),
                new ApiDescriptor(
                        ID_LIST, "医生清单", SERVICE, "POST", PATH_LIST, ApiKind.READ, "doctor", Map.of(), true, null, Map.of()),
                new ApiDescriptor(
                        ID_EXPORT, "导出报表", SERVICE, "POST", PATH_EXPORT, ApiKind.WRITE, "report", Map.of(), true, null, Map.of()));
        return new ApiRegistry() {
            @Override
            public Optional<ApiDescriptor> findByRoute(String service, String httpMethod, String httpPath) {
                ApiRoute route = new ApiRoute(service, httpMethod, httpPath);
                return apis.stream().filter(api -> api.route().equals(route)).findFirst();
            }

            @Override
            public List<ApiDescriptor> findByIds(Collection<Long> ids) {
                return apis.stream().filter(api -> ids.contains(api.id())).toList();
            }
        };
    }

    /** 下游替身：记录下发的信封并返回一份成功的行数据。 */
    @TestConfiguration
    public static class RecordingDownstream {

        @Bean
        @Primary
        public RecordingDownstreamClient recordingDownstreamClient() {
            return new RecordingDownstreamClient();
        }
    }

    public static final class RecordingDownstreamClient implements DownstreamClient {

        private final List<ApiEnvelope<Map<String, Object>>> calls =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public DownstreamResponse send(ApiEnvelope<Map<String, Object>> envelope, ApiDescriptor descriptor) {
            calls.add(envelope);
            return new DownstreamResponse(200, "{\"columns\":[\"doctor_id\"],\"rows\":[[7]],\"rowCount\":1}");
        }

        public List<ApiEnvelope<Map<String, Object>>> calls() {
            return List.copyOf(calls);
        }

        public ApiEnvelope<Map<String, Object>> lastCall() {
            return calls.get(calls.size() - 1);
        }

        public void clear() {
            calls.clear();
        }
    }

    public static final class RecordingAuditWriter implements PermissionAuditWriter {

        private final List<PermissionAuditEntry> entries =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(PermissionAuditEntry entry) {
            entries.add(entry);
        }

        public List<PermissionAuditEntry> entries() {
            return List.copyOf(entries);
        }

        /** 取某条接口路由的最后一条记录（{@code toolName} = {@code 方法 + 路径}）。 */
        public Optional<PermissionAuditEntry> lastFor(String routeLabel) {
            return entries.stream().filter(e -> routeLabel.equals(e.toolName())).reduce((a, b) -> b);
        }

        public void clear() {
            entries.clear();
        }
    }

    public static final class InMemoryConfirmStore implements ConfirmStore {

        private final Map<String, ConfirmRecord> records = new ConcurrentHashMap<>();
        private final Set<String> consumed = ConcurrentHashMap.newKeySet();

        public void put(String confirmId, String userId) {
            records.put(
                    confirmId,
                    new ConfirmRecord(
                            confirmId,
                            "s-1",
                            "t-1",
                            userId,
                            PATH_EXPORT,
                            "导出报表",
                            ConfirmStatus.PENDING,
                            Instant.now().plus(Duration.ofMinutes(10))));
        }

        @Override
        public Optional<ConfirmRecord> find(String confirmId) {
            return Optional.ofNullable(records.get(confirmId));
        }

        @Override
        public boolean consume(String confirmId, String userId) {
            ConfirmRecord record = records.get(confirmId);
            if (record == null || !record.userId().equals(userId)) {
                return false;
            }
            return consumed.add(confirmId);
        }
    }

    /** 授权数据：{@code role_api} 与 {@code skill_api} 都只贡献 {@code sys_api.id}。 */
    public static PermissionRepository standardPermissionRepository() {
        Map<String, List<String>> rolesByUser = Map.of(ALICE, List.of("dept_a", "dept_b"), BOB, List.of("finance"));
        Map<String, Set<String>> skillsByRole = Map.of("dept_a", Set.of("perf_report"), "dept_b", Set.of("perf_report"));
        Map<String, Set<Long>> apisBySkill = Map.of("perf_report", Set.of(ID_PERFORMANCE));
        Map<String, Set<Long>> directApisByRole = Map.of(
                "dept_a", Set.of(ID_LIST),
                "dept_b", Set.of(ID_LIST, ID_PERFORMANCE),
                "finance", Set.of(ID_LIST, ID_EXPORT));

        return new PermissionRepository() {
            @Override
            public List<String> roleIdsOf(String userId) {
                return rolesByUser.getOrDefault(userId, List.of());
            }

            @Override
            public List<String> skillCodesOfRoles(List<String> roleIds) {
                Set<String> result = new LinkedHashSet<>();
                roleIds.forEach(role -> result.addAll(skillsByRole.getOrDefault(role, Set.of())));
                return List.copyOf(result);
            }

            @Override
            public List<String> personalSkillCodesOf(String userId) {
                return List.of();
            }

            @Override
            public List<Long> approvedApiIdsOfSkill(String skillCode) {
                return List.copyOf(apisBySkill.getOrDefault(skillCode, Set.of()));
            }

            @Override
            public List<Long> directApiIdsOfRoles(List<String> roleIds) {
                Set<Long> result = new LinkedHashSet<>();
                roleIds.forEach(role -> result.addAll(directApisByRole.getOrDefault(role, Set.of())));
                return List.copyOf(result);
            }
        };
    }
}