package com.djzy.assistant.spi.tool;

import com.djzy.assistant.spi.AgentErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用结果（管线 RESULT 段冻结后的只读观察对象，§9.5）。
 *
 * @param status 状态
 * @param content 模型可见文本（POST 段可能已替换 / 截断 / 脱敏复检）
 * @param data 结构化数据（如接口原始行；大结果已 spill 时只保留 locator）
 * @param errorCode 归一化错误码（失败时）
 * @param errorMessage 对内诊断信息（不直接下发前端）
 * @param meta 元数据：数字台账编号、spill locator、行数、耗时、口径标注等
 */
public record ToolInvocationResult(
        ToolInvocationStatus status,
        String content,
        Map<String, Object> data,
        AgentErrorCode errorCode,
        String errorMessage,
        Map<String, Object> meta) {

    public ToolInvocationResult {
        Objects.requireNonNull(status, "status");
        content = content == null ? "" : content;
        data = data == null ? Map.of() : Map.copyOf(data);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static ToolInvocationResult ok(String content, Map<String, Object> data) {
        return new ToolInvocationResult(ToolInvocationStatus.OK, content, data, null, null, Map.of());
    }

    public static ToolInvocationResult ok(String content, Map<String, Object> data, Map<String, Object> meta) {
        return new ToolInvocationResult(ToolInvocationStatus.OK, content, data, null, null, meta);
    }

    public static ToolInvocationResult denied(String userFacingMessage, String reason) {
        return new ToolInvocationResult(
                ToolInvocationStatus.DENIED, userFacingMessage, Map.of(), AgentErrorCode.TOOL_DENIED, reason, Map.of());
    }

    public static ToolInvocationResult error(AgentErrorCode code, String message) {
        return new ToolInvocationResult(ToolInvocationStatus.ERROR, "", Map.of(), code, message, Map.of());
    }

    public static ToolInvocationResult timeout(String message) {
        return new ToolInvocationResult(
                ToolInvocationStatus.TIMEOUT, "", Map.of(), AgentErrorCode.TOOL_TIMEOUT, message, Map.of());
    }

    public static ToolInvocationResult awaitingConfirm(String confirmId, String summary) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("confirmId", confirmId);
        meta.put("summary", summary);
        return new ToolInvocationResult(ToolInvocationStatus.AWAITING_CONFIRM, summary, Map.of(), null, null, meta);
    }

    public boolean isSuccess() {
        return status == ToolInvocationStatus.OK;
    }
}
