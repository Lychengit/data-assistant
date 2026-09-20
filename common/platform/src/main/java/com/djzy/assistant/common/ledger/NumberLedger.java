package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数字台账（§6.2）：按轮次建立，工具结果的每个数字登记后拿到稳定编号。
 *
 * <p>台账随工具结果一并交给模型（编号 + 名称 + 数值 + 单位的最小必要部分），
 * 并随该轮答案落 {@code conversation_turn.ledger_json}（§8.3），供回放与校验。
 */
public final class NumberLedger {

    private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** 登记一个数字，返回其编号（{@code A1} / {@code A2} / …）。 */
    public synchronized String register(
            BigDecimal value, String unit, String metricKey, String timeRange, String basis, String apiCode, String display) {
        String ref = "A" + counter.incrementAndGet();
        entries.put(ref, new LedgerEntry(ref, value, unit, metricKey, timeRange, basis, apiCode, display));
        return ref;
    }

    public synchronized String register(BigDecimal value, String unit, String metricKey) {
        return register(value, unit, metricKey, null, null, null, null);
    }

    public synchronized Optional<LedgerEntry> get(String ref) {
        return Optional.ofNullable(entries.get(ref));
    }

    public synchronized Map<String, LedgerEntry> entries() {
        return Map.copyOf(entries);
    }

    public synchronized boolean contains(String ref) {
        return entries.containsKey(ref);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        entries.forEach((k, v) -> m.put(k, v.toMap()));
        return m;
    }
}
