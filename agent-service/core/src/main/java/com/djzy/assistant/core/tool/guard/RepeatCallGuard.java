package com.djzy.assistant.core.tool.guard;

import com.djzy.assistant.common.pipeline.ToolGuard;
import com.djzy.assistant.common.pipeline.ToolInvocationContext;
import java.util.Optional;
import java.util.TreeMap;

/**
 * GUARD 段：重复调用熔断（§9.5 / §10.4-2）——同一轮内同工具同参数重复超过阈值即拒绝，防死循环。
 */
public final class RepeatCallGuard implements ToolGuard {

    private final RepeatCallStore store;
    private final int maxRepeats;

    public RepeatCallGuard(RepeatCallStore store, int maxRepeats) {
        this.store = store;
        this.maxRepeats = maxRepeats;
    }

    @Override
    public Optional<String> check(ToolInvocationContext context) {
        if (context.turnId() == null) {
            return Optional.empty();
        }
        String signature = context.toolName() + ":" + canonicalArgs(context);
        int count = store.incrementAndGet(context.userId(), context.turnId(), signature);
        if (count > maxRepeats) {
            return Optional.of("REPEAT_CALL_CIRCUIT_OPEN:" + count + "/" + maxRepeats);
        }
        return Optional.empty();
    }

    private static String canonicalArgs(ToolInvocationContext context) {
        return new TreeMap<>(context.arguments()).toString();
    }
}
