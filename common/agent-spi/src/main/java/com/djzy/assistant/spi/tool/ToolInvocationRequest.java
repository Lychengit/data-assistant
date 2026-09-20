package com.djzy.assistant.spi.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 运行时 → 平台的工具调用委派请求（ADR-32 ③：运行时手里只有一个回调）。
 *
 * @param toolName 工具名
 * @param arguments 模型给出的入参（结构化白名单，需在管线 PRE 段校验）
 * @param userId 平台注入的可信身份（运行时不可改写）
 * @param sessionId 会话 id
 * @param turnId 轮次 id
 * @param traceId 链路 id
 * @param requestId 请求 id（审计串联）
 * @param confirmId 已确认项 id（写操作所需，一次性，§19.9）
 * @param deadlineEpochMs 绝对截止时间（超时预算链，§9.1）
 * @param attributes 扩展上下文
 */
public record ToolInvocationRequest(
        String toolName,
        Map<String, Object> arguments,
        String userId,
        String sessionId,
        String turnId,
        String traceId,
        String requestId,
        String confirmId,
        long deadlineEpochMs,
        Map<String, Object> attributes) {

    public ToolInvocationRequest {
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        Objects.requireNonNull(userId, "userId");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
