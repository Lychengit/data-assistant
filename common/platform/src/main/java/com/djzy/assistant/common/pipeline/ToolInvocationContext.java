package com.djzy.assistant.common.pipeline;

import com.djzy.assistant.spi.tool.SideEffect;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 管线上下文：贯穿 PRE → GUARD → EXECUTE → POST → RESULT。
 *
 * <p>被保护的身份字段（userId / sessionId）由平台注入，GUARD 段看不到也改不了。
 */
public final class ToolInvocationContext {

    private final String toolName;
    private final String userId;
    private final String sessionId;
    private final String turnId;
    private final String traceId;
    private final String requestId;
    private final SideEffect sideEffect;
    private final long deadlineEpochMs;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Map<String, Object> arguments;

    public ToolInvocationContext(
            String toolName,
            Map<String, Object> arguments,
            String userId,
            String sessionId,
            String turnId,
            String traceId,
            String requestId,
            SideEffect sideEffect,
            long deadlineEpochMs) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.traceId = traceId;
        this.requestId = requestId;
        this.sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
        this.deadlineEpochMs = deadlineEpochMs;
    }

    public String toolName() {
        return toolName;
    }

    public Map<String, Object> arguments() {
        return Map.copyOf(arguments);
    }

    /** 仅 PRE 段可用：改写本次调用参数（例如「上月」→ 具体月份区间）。 */
    public void rewriteArguments(Map<String, Object> newArguments) {
        this.arguments = newArguments == null ? Map.of() : new LinkedHashMap<>(newArguments);
    }

    public String userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String turnId() {
        return turnId;
    }

    public String traceId() {
        return traceId;
    }

    public String requestId() {
        return requestId;
    }

    public SideEffect sideEffect() {
        return sideEffect;
    }

    public long deadlineEpochMs() {
        return deadlineEpochMs;
    }

    public long remainingMillis() {
        return deadlineEpochMs <= 0 ? Long.MAX_VALUE : deadlineEpochMs - System.currentTimeMillis();
    }

    public boolean expired() {
        return remainingMillis() <= 0;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String key, T defaultValue) {
        Object v = attributes.get(key);
        return v == null ? defaultValue : (T) v;
    }
}
