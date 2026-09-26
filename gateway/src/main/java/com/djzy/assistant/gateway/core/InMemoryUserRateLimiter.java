package com.djzy.assistant.gateway.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例内存实现（本机开发 / 单副本骨架场景）。
 *
 * <p>**必须明示的代价**：多副本部署下每个实例各数一份，实际放行量 = 副本数 × 配置的 qps。
 * 所以生产 / 多副本必须换 {@link RedisUserRateLimiter}（配置项 {@code gateway.rate-limit-store=redis}）。
 *
 * <p>计时口径是**固定窗口**：每满 1000 毫秒重开一班，窗口交界处最多放过两倍额度。
 * 对「每次数据调用前先挡一下」这个用途完全够用，也省掉了在 Redis 里维护滑动窗口的复杂度。
 */
public final class InMemoryUserRateLimiter implements UserRateLimiter {

    private final int permitsPerSecond;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryUserRateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    @Override
    public boolean tryAcquire(String key) {
        return tryAcquireAt(key, System.currentTimeMillis());
    }

    /** 带上时间戳的版本：单测要能把「下一秒」直接喂进来，不必真的 sleep 一秒。 */
    boolean tryAcquireAt(String key, long nowMillis) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Window window = windows.computeIfAbsent(key, k -> new Window(nowMillis));
        synchronized (window) {
            if (nowMillis - window.startMillis >= 1000L) {
                window.startMillis = nowMillis;
                window.count = 0;
            }
            if (window.count >= permitsPerSecond) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    private static final class Window {
        private long startMillis;
        private int count;

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}