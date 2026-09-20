package com.djzy.assistant.gateway.core;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.InternalCallCodec;
import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceSigner;
import com.djzy.assistant.gateway.config.GatewayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G2 的 HTTP 实现：每次尝试**重新生成时间戳与 nonce 并重新签名**（§18.4.2 G2），
 * 绝不复用已消费的 nonce；写操作**绝不自动重试**。
 */
public final class HttpDownstreamClient implements DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDownstreamClient.class);

    private final ServiceRouter router;
    private final ServiceCredential credential;
    private final HttpClient client;
    private final int readRetries;
    private final Duration readTimeout;
    private final Duration writeTimeout;

    public HttpDownstreamClient(
            ServiceRouter router, ServiceCredential credential, GatewayProperties properties) {
        this(
                router,
                credential,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                properties.getReadRetries(),
                properties.getReadTimeout(),
                properties.getWriteTimeout());
    }

    public HttpDownstreamClient(
            ServiceRouter router,
            ServiceCredential credential,
            HttpClient client,
            int readRetries,
            Duration readTimeout,
            Duration writeTimeout) {
        this.router = router;
        this.credential = credential;
        this.client = client;
        this.readRetries = Math.max(0, readRetries);
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
    }

    @Override
    public DownstreamResponse send(ApiEnvelope<Map<String, Object>> envelope, ApiDescriptor descriptor) {
        String body = InternalCallCodec.toJson(envelope);
        boolean write = descriptor.kind().isWrite();
        int attempts = write ? 1 : readRetries + 1;
        Duration timeout = write ? writeTimeout : readTimeout;
        URI uri = router.uriFor(descriptor);

        DownstreamUnavailableException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                sleepQuietly(100L << (attempt - 1));
            }
            try {
                Map<String, String> signature = ServiceSigner.sign(credential, "POST", uri.getPath(), Map.of(), body);
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                signature.forEach(builder::header);
                HttpResponse<String> response =
                        client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 500 && attempt < attempts - 1) {
                    last = new DownstreamUnavailableException(false, "下游 5xx：" + response.statusCode(), null);
                    log.warn("接口服务返回 {}，重试 {}/{}（route={}）",
                            response.statusCode(), attempt + 1, attempts, descriptor.route());
                    continue;
                }
                return new DownstreamResponse(response.statusCode(), response.body());
            } catch (java.net.http.HttpTimeoutException e) {
                last = new DownstreamUnavailableException(true, "接口服务超时：" + uri, e);
            } catch (java.io.IOException e) {
                last = new DownstreamUnavailableException(false, "接口服务不可达：" + uri, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownstreamUnavailableException(false, "转发被中断：" + uri, e);
            }
        }
        throw last;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
