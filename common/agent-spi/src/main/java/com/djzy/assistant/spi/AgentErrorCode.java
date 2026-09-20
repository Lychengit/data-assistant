package com.djzy.assistant.spi;

/**
 * 归一化错误码（TCK-12）：框架异常一律映射到这些码，对外不泄露内部堆栈。
 *
 * <p>对外措辞统一由 §4.5 决定（如「未找到您有权查看的相关数据」），差异只进审计。
 */
public enum AgentErrorCode {
    LLM_UNAVAILABLE,
    LLM_TIMEOUT,
    DEADLINE_EXCEEDED,
    TOOL_DENIED,
    TOOL_TIMEOUT,
    TOOL_FAILED,
    TOOL_CONFIRM_REQUIRED,
    MAX_ITERS_EXCEEDED,
    CANCELED,
    RUNTIME_MISMATCH,
    RUNTIME_UNAVAILABLE,
    INVALID_REQUEST,
    INTERNAL
}
