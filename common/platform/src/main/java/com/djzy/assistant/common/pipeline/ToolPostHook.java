package com.djzy.assistant.common.pipeline;

import com.djzy.assistant.spi.tool.ToolInvocationResult;

/**
 * POST 段钩子：结果级处置（§9.5）。
 *
 * <p>落点示例：大结果 spill 只回 locator（§18.4.3 S4）、行数截断、脱敏兜底复检、跑偏信号拦截（§10.4）。
 */
public interface ToolPostHook {

    PostOutcome apply(ToolInvocationContext context, ToolInvocationResult result);
}
