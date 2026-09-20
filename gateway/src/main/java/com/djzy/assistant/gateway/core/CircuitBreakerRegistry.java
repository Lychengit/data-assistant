package com.djzy.assistant.gateway.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按接口熔断（§18.4.2 G4 / §9.2）：持续失败就暂时切断，避免一个坏接口拖垮整条链路。
 *
 * <p>骨架期单实例内存实现；多副本各自熔断（可接受，§9.2 未要求全局一致）。
 */
public final class CircuitBreakerRegistry {

    private final int failureThreshold;
    private final long openMillis;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(int failureThreshold, java.time.Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1L, openDuration.toMillis());
    }

    public Breaker forKey(String key) {
        return breakers.computeIfAbsent(key, k -> new Breaker(failureThreshold, openMillis));
    }

    public static final class Breaker {

        private final int failureThreshold;
        private final long openMillis;
        private int consecutiveFailures;
        private long openUntil;

        Breaker(int failureThreshold, long openMillis) {
            this.failureThreshold = failureThreshold;
            this.openMillis = openMillis;
        }

        public synchronized boolean allowRequest() {
            return System.currentTimeMillis() >= openUntil;
        }

        public synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openUntil = 0;
        }

        public synchronized void recordFailure() {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                openUntil = System.currentTimeMillis() + openMillis;
                consecutiveFailures = 0;
            }
        }

        public synchronized boolean isOpen() {
            return System.currentTimeMillis() < openUntil;
        }
    }
}
