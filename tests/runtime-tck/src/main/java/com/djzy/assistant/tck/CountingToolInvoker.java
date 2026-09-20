package com.djzy.assistant.tck;

import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvoker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** 计数工具调用器：TCK 用它断言「工具调用 100% 经 ToolInvoker」。 */
public final class CountingToolInvoker implements ToolInvoker {

    private final Function<ToolInvocationRequest, ToolInvocationResult> responder;
    private final List<ToolInvocationRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    public CountingToolInvoker(Function<ToolInvocationRequest, ToolInvocationResult> responder) {
        this.responder = responder;
    }

    @Override
    public Publisher<ToolInvocationResult> invoke(ToolInvocationRequest request) {
        requests.add(request);
        calls.incrementAndGet();
        return Mono.just(responder.apply(request));
    }

    public int callCount() {
        return calls.get();
    }

    public List<ToolInvocationRequest> requests() {
        return new ArrayList<>(requests);
    }
}
