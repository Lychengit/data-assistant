package com.djzy.assistant.agentweb.gateway;

import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceSigner;
import com.djzy.assistant.common.security.SignatureHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * agent-service → 网关的**唯一**出网通道（§19.7 / §20.1.2）。
 *
 * <p>签名由 {@link ServiceSigner} 生成（业务代码不得自行拼签名串），
 * 用户身份只以 {@code Authorization} 头透传——网关不接受调用方自带 userId / 角色 / 范围（§20.1.6-5）。
 */
public final class GatewayClient {

    private final String baseUrl;
    private final ServiceCredential credential;
    private final Duration readTimeout;
    private final HttpClient http;

    public GatewayClient(String baseUrl, ServiceCredential credential, Duration connectTimeout, Duration readTimeout) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.credential = credential;
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Response get(String path, String bearerToken) {
        return send("GET", path, Map.of(), null, bearerToken);
    }

    public Response postJson(String path, String jsonBody, String bearerToken) {
        return send("POST", path, Map.of(), jsonBody, bearerToken);
    }

    /**
     * @param path 不含 base 的请求路径（签名串里用的就是它）
     * @param jsonBody 请求体（null 表示无体）；签名覆盖它的 SHA-256
     */
    public Response send(
            String method,
            String path,
            Map<String, List<String>> query,
            String jsonBody,
            String bearerToken) {
        String body = jsonBody == null ? "" : jsonBody;
        Map<String, String> headers = ServiceSigner.sign(credential, method, path, query, body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path + queryString(query)))
                .timeout(readTimeout)
                .header(SignatureHeaders.API_KEY, headers.get(SignatureHeaders.API_KEY))
                .header(SignatureHeaders.TIMESTAMP, headers.get(SignatureHeaders.TIMESTAMP))
                .header(SignatureHeaders.NONCE, headers.get(SignatureHeaders.NONCE))
                .header(SignatureHeaders.SIGNATURE, headers.get(SignatureHeaders.SIGNATURE))
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new GatewayUnavailableException(readTimeout.toMillis(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException(readTimeout.toMillis(), e);
        }
    }

    private static String queryString(Map<String, List<String>> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        query.forEach((key, values) -> {
            for (String value : values) {
                if (sb.length() > 1) {
                    sb.append('&');
                }
                sb.append(key).append('=').append(value);
            }
        });
        return sb.toString();
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 网关应答（原样保留状态码与体，供工具层映射成统一结果）。 */
    public record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 网关不可达 / 超时（§9.2 降级矩阵：LLM 与数据面故障都要给统一措辞，不静默失败）。 */
    public static final class GatewayUnavailableException extends RuntimeException {
        private final boolean timeout;

        public GatewayUnavailableException(long timeoutMs, Throwable cause) {
            super(cause);
            this.timeout = cause instanceof java.net.http.HttpTimeoutException
                    || (cause instanceof IOException && cause.getMessage() != null
                            && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timed out"));
        }

        public boolean isTimeout() {
            return timeout;
        }
    }
}
