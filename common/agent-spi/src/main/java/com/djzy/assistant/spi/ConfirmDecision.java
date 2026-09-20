package com.djzy.assistant.spi;

import java.util.Objects;

/**
 * HITL 确认结果（§19.9）：确认项一次性、绑定唯一待确认项。
 *
 * @param confirmId 待确认项 id（一次性、带 TTL、绑定具体执行工单与写动作）
 * @param approved 是否批准
 * @param userText 用户自然语言答复原文（后端必须已解析并绑定到唯一待确认项）
 */
public record ConfirmDecision(String confirmId, boolean approved, String userText, long respondedAtEpochMs) {

    public ConfirmDecision {
        Objects.requireNonNull(confirmId, "confirmId");
        userText = userText == null ? "" : userText;
    }

    public static ConfirmDecision approve(String confirmId, String userText) {
        return new ConfirmDecision(confirmId, true, userText, System.currentTimeMillis());
    }

    public static ConfirmDecision reject(String confirmId, String userText) {
        return new ConfirmDecision(confirmId, false, userText, System.currentTimeMillis());
    }
}
