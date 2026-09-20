package com.djzy.assistant.iface.doctor;

import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.api.InternalCallCodec;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 接口服务测试基座：用 JDK {@link HttpClient} 发原始请求。
 *
 * <p>必须用原始 HTTP 而不是 RestTemplate / MockMvc 包装：I1 验签算的是
 * 「请求体字节的 SHA-256 + 方法 + 路径 + query + 时间戳 + nonce」，任何重新序列化都会让签名对不上。
 */
public final class InterfaceDoctorTestSupport {

    public static final String GATEWAY_SECRET = "iface-gateway-test-secret";
    public static final ServiceCredential GATEWAY = new ServiceCredential("gateway", GATEWAY_SECRET);

    /** 接口路径即身份的一部分（§4.7）：调用方直说路径，没有可读编码这一层。 */
    public static final String PATH_PERFORMANCE = "/doctor/performance";
    public static final String PATH_LIST = "/doctor/list";

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private InterfaceDoctorTestSupport() {}

    public record HttpResult(int status, String body) {}

    /** 构造网关下发的信封：{@code caller}（可信身份）+ {@code args}（业务入参）两段。 */
    public static String envelope(String userId, String httpPath, Map<String, Object> args) {
        return envelope(userId, httpPath, args, null, null);
    }

    public static String envelope(
            String userId, String httpPath, Map<String, Object> args, String skillCode, String confirmId) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("userId", userId);
        caller.put("requestId", "req-" + UUID.randomUUID());
        caller.put("traceId", "trace-1");
        caller.put("skillCode", skillCode);
        caller.put("confirmId", confirmId);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("caller", caller);
        raw.put("args", args == null ? Map.of() : args);
        return InternalCallCodec.toJson(raw);
    }

    /**
     * 用**网关那一侧的真实类型**构造信封（{@link ApiEnvelope} + {@link CallerInfo} 交给
     * {@link InternalCallCodec} 序列化），而不是手写 Map。
     *
     * <p>为什么要有这个重载：手写 Map 的用例只能证明"接口服务收得下我自己编的 JSON"，
     * 证明不了"网关写出来的 JSON 接口服务收得下"。两侧的字段集一旦漂移（例如某个 record 上多了一个
     * {@code isXxx()} 派生访问器被 Jackson 当属性写出去），接口服务的 {@code fail-on-unknown-properties}
     * 会 fail-closed 整单拒收，而两边各自的单测都是绿的——只有这条缝上的用例会红。
     */
    public static String wireEnvelope(CallerInfo caller, Map<String, Object> args) {
        return InternalCallCodec.toJson(new ApiEnvelope<>(caller, args == null ? Map.of() : args));
    }

    public static String pathOf(String declaredPath) {
        return declaredPath;
    }

    /** 网关正常调用：当前时间戳 + 全新 nonce 的签名。 */
    public static HttpResult signedPost(String baseUrl, String httpPath, String body) {
        return post(baseUrl, httpPath, body, ServiceSigner.sign(GATEWAY, "POST", httpPath, Map.of(), body));
    }

    /** 指定时间戳与 nonce（重放 / 过期 / 篡改用例）。 */
    public static HttpResult signedPostWith(
            String baseUrl, String path, String body, ServiceCredential credential, long timestampSeconds, String nonce) {
        return post(
                baseUrl,
                path,
                body,
                ServiceSigner.sign(credential, "POST", path, Map.of(), body, timestampSeconds, nonce));
    }

    public static HttpResult post(String baseUrl, String path, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response =
                    HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("调用接口服务失败：" + path, e);
        }
    }
}