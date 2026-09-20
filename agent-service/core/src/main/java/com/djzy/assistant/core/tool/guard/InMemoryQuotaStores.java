package com.djzy.assistant.core.tool.guard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单测用的本地计数实现。
 *
 * <p><b>不得用于生产</b>：多副本下限流口径会失真（§2.3 / §9.3）；生产必须用 Redis 原子计数实现。
 */
public final class InMemoryQuotaStores implements WriteQuotaStore, RepeatCallStore {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public int incrementAndGet(String userId, String turnId) {
        return counters.computeIfAbsent("write:" + userId + ":" + turnId, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public int incrementAndGet(String userId, String turnId, String callSignature) {
        return counters
                .computeIfAbsent("repeat:" + userId + ":" + turnId + ":" + callSignature, k -> new AtomicInteger())
                .incrementAndGet();
    }
}
