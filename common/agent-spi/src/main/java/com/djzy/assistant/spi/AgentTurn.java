package com.djzy.assistant.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次用户轮次的中立描述。
 *
 * @param turnId 轮次 id（事件与落库的必填字段）
 * @param traceId 全链路 trace id
 * @param userText 用户输入原文
 * @param attachments 附件引用（对象存储 locator），骨架期可为空
 * @param attributes 额外上下文（如解析后的时间区间、消歧后的医生 id）；
 *     取值见 {@link #ATTR_CONTEXT_REMINDER} 这类保留键
 */
public record AgentTurn(
        String turnId,
        String traceId,
        String userText,
        List<String> attachments,
        Map<String, Object> attributes) {

    /**
     * {@code attributes} 的保留键：平台给模型附的「本轮上下文快照」文本。
     *
     * <p>为什么要贴到**提问旁边**而不是只写在系统提示词里：系统提示词在消息列表最前面，
     * 而老会话的历史紧挨着新提问。实测（admin 撤销 `doctor_list` 授权后在同一会话里再问
     * 「你有哪些能力」）：提示词里已经写明「本轮可用接口=1 个、以它为准」，模型照样把上一轮
     * 自己列的「医生名单查询」原样又答了一遍——离提问最近的旧答案压过了开头的规则。
     * 所以把同一份清单再贴一次到用户消息之后，用最近的位置兜住它。
     *
     * <p>平台日志不受影响：append-only 事件里记的仍是用户原文，这段只在运行时构造模型消息时拼上。
     */
    public static final String ATTR_CONTEXT_REMINDER = "contextReminder";

    public AgentTurn {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(userText, "userText");
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AgentTurn of(String turnId, String traceId, String userText) {
        return new AgentTurn(turnId, traceId, userText, List.of(), Map.of());
    }
}