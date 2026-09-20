package com.djzy.assistant.gateway.core;

/** 接口服务不可用（超时 / 连接失败 / 5xx）：网关据此熔断、降级并写审计。 */
public class DownstreamUnavailableException extends RuntimeException {

    private final boolean timeout;

    public DownstreamUnavailableException(boolean timeout, String message, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
