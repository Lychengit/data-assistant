package com.djzy.assistant.common.pipeline;

import com.djzy.assistant.spi.tool.ToolInvocationResult;

/**
 * EXECUTE 段：包裹真正的调用（§9.5）。
 *
 * <p>落入此段的是：直连接口（经网关，HMAC 签名）、技能包执行、沙箱脚本、平台内置工具——
 * 全部走同一段，不得另立特例。超时、只读幂等重试、耗时与 token 打点都在这里。
 */
@FunctionalInterface
public interface ToolExecution {

    ToolInvocationResult execute(ToolInvocationContext context) throws Exception;
}
