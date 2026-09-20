package com.djzy.assistant.common.pipeline;

/**
 * PRE 段钩子：放行前的准备与询问（§9.5）。
 *
 * <p>落点示例：网关下发 scope 注入、写操作确认（§19.9 {@code confirmId}）、技能启动前一次确认。
 */
public interface ToolPreHook {

    PreOutcome apply(ToolInvocationContext context);
}
