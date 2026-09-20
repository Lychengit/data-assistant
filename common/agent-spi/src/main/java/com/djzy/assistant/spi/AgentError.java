package com.djzy.assistant.spi;

import java.util.Map;
import java.util.Objects;

/**
 * 归一化错误。
 *
 * @param code 错误码
 * @param userMessage 可直接展示给用户的统一措辞
 * @param detail 内部诊断信息（仅进日志 / 审计，不下发前端，不泄露堆栈）
 */
public record AgentError(AgentErrorCode code, String userMessage, String detail, Map<String, Object> attributes) {

    public AgentError {
        Objects.requireNonNull(code, "code");
        userMessage = userMessage == null ? "" : userMessage;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AgentError of(AgentErrorCode code, String userMessage) {
        return new AgentError(code, userMessage, null, Map.of());
    }
}
