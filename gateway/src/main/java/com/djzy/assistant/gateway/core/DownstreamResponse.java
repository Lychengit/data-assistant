package com.djzy.assistant.gateway.core;

/** 接口服务返回的原始响应（状态码 + 响应体，不改写业务语义）。 */
public record DownstreamResponse(int status, String body) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
