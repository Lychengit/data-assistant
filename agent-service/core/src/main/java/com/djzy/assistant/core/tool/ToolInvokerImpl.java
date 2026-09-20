package com.djzy.assistant.core.tool;

import com.djzy.assistant.common.pipeline.ToolInvocationContext;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvoker;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 平台唯一工具执行出口（ADR-32 ③ / §18.11.3）：运行时把每次工具调用委派到这里。
 *
 * <p>运行时手里没有 MCP 客户端、没有 DB、没有网关密钥，只有一个回调对象——
 * **它想绕过也绕不过，不是靠它自觉，是靠它手里根本没有工具**。
 */
public final class ToolInvokerImpl implements ToolInvoker {

    private final ToolRegistry registry;
    private final Function<com.djzy.assistant.common.pipeline.ToolDescriptor, ToolPipelineRunner> runnerFactory;

    public ToolInvokerImpl(
            ToolRegistry registry,
            Function<com.djzy.assistant.common.pipeline.ToolDescriptor, ToolPipelineRunner> runnerFactory) {
        this.registry = registry;
        this.runnerFactory = runnerFactory;
    }

    @Override
    public Publisher<ToolInvocationResult> invoke(ToolInvocationRequest request) {
        return Mono.fromCallable(() -> invokeBlocking(request)).subscribeOn(Schedulers.boundedElastic());
    }

    /** 同步执行（管线是阻塞式的：EXECUTE 段内部自行处理超时与重试预算）。 */
    public ToolInvocationResult invokeBlocking(ToolInvocationRequest request) {
        ToolRegistry.RegisteredTool registered = registry.find(request.toolName()).orElse(null);
        if (registered == null) {
            return ToolInvocationResult.denied(
                    com.djzy.assistant.common.error.UnifiedErrors.FORBIDDEN, "TOOL_NOT_VISIBLE");
        }
        ToolInvocationContext context = new ToolInvocationContext(
                request.toolName(),
                request.arguments(),
                request.userId(),
                request.sessionId(),
                request.turnId(),
                request.traceId(),
                request.requestId(),
                registered.descriptor().sideEffect(),
                request.deadlineEpochMs());
        if (request.confirmId() != null) {
            context.attributes().put("confirmId", request.confirmId());
        }
        request.attributes().forEach(context.attributes()::put);
        return runnerFactory.apply(registered.descriptor()).run(context);
    }

    static ToolInvocationResult notVisible(String toolName) {
        return ToolInvocationResult.error(AgentErrorCode.TOOL_DENIED, "工具不可见：" + toolName);
    }
}
