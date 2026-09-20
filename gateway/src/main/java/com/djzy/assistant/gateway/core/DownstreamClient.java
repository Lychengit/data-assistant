package com.djzy.assistant.gateway.core;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiEnvelope;
import java.util.Map;

/** G2 转发端口：把已签名（且已判定放行）的下发请求送到接口服务（§18.4.2 G2）。 */
@FunctionalInterface
public interface DownstreamClient {

    /**
     * @param envelope 网关下发的信封：{@code caller}（可信身份）+ {@code args}（模型给的业务入参）
     * @throws DownstreamUnavailableException 超时 / 连接失败 / 下游 5xx（调用方据此熔断并降级）
     */
    DownstreamResponse send(ApiEnvelope<Map<String, Object>> envelope, ApiDescriptor descriptor);
}