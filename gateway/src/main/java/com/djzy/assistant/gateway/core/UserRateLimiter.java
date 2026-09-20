package com.djzy.assistant.gateway.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户级限速（§18.4.2 G4 / §9.3）：骨架期单实例滑动窗口，够用且不引入 Redis 依赖。
 *
 * <p>多副本部署时每副本各自计数（上限 = 副本数 × qps），需要全局口径时换 Redis 计数器；
 * 这一点在部署清单里明示（§9.3）。
 */
public final class UserRateLimiter {

    private final int permitsPerSecond;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public UserRateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    public boolean tryAcquire(String key) {
        return tryAcquireAt(key, System.currentTimeMillis());
    }

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
