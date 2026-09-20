package com.djzy.assistant.common.pipeline;

import com.djzy.assistant.spi.tool.ToolInvocationResult;

/**
 * RESULT 段观察者：**只读**（§9.5）。
 *
 * <p>落点示例：数字台账登记（§6.2）、审计落库、SSE {@code tool_result} 投影（§12.2）。
 * 实现不得修改任何内容，也不得抛异常影响主流程。
 */
@FunctionalInterface
public interface ToolResultObserver {

    void observe(ToolInvocationContext context, ToolInvocationResult result);
}
