package com.djzy.assistant.spi.tool;

import org.reactivestreams.Publisher;

/**
 * 平台唯一工具执行出口（ADR-32 ③ / §9.5）：运行时不得自行执行工具、不得持有数据面凭据。
 *
 * <p>实现必须走平台工具执行管线（PRE → GUARD → EXECUTE → POST → RESULT）。
 */
@FunctionalInterface
public interface ToolInvoker {

    Publisher<ToolInvocationResult> invoke(ToolInvocationRequest request);
}
