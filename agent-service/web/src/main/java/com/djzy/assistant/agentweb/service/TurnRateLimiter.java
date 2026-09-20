package com.djzy.assistant.agentweb.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单实例滑动窗口限流（§9.3）：一分钟内可发起的轮次数上限。
 *
 * <p>**只留给单副本的骨架场景**：计数在进程内，多副本各算一份、口径失真，
 * 所以规格禁止本地内存计数（§2.3）；多副本必须装配 {@link RedisTurnLimiter}。
 * 这里保留它是因为「只有 PG 也要能跑」——但要清楚它换来的是口径失真的风险。
 */
public final class TurnRateLimiter implements TurnLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final int permitsPerWindow;
    private final ConcurrentMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public TurnRateLimiter(int permitsPerWindow) {
        this.permitsPerWindow = permitsPerWindow <= 0 ? Integer.MAX_VALUE : permitsPerWindow;
    }

    @Override
    public boolean tryAcquire(String userId) {
        if (userId == null || permitsPerWindow == Integer.MAX_VALUE) {
            return true;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;
        Deque<Long> window = windows.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }
            if (window.size() >= permitsPerWindow) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 观测用。 */
    @Override
    public Map<String, Integer> windows() {
        Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
        windows.forEach((user, window) -> {
            synchronized (window) {
                sizes.put(user, window.size());
            }
        });
        return sizes;
    }
}
