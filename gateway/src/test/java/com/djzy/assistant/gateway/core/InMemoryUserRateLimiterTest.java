package com.djzy.assistant.gateway.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 单实例内存限速（本机开发退路）：语义必须和 Redis 版一致，只差「计数不共享」。 */
class InMemoryUserRateLimiterTest {

    private final InMemoryUserRateLimiter limiter = new InMemoryUserRateLimiter(2);

    @Test
    void 每秒最多放行配置的条数() {
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("alice", 1100));
        assertFalse(limiter.tryAcquireAt("alice", 1200));
    }

    @Test
    void 下一窗口重新放开() {
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("alice", 1100));
        assertTrue(limiter.tryAcquireAt("alice", 2001));
    }

    @Test
    void 按用户各算一份() {
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("bob", 1000));
    }

    @Test
    void 空用户直接拒绝() {
        assertFalse(limiter.tryAcquireAt(" ", 1000));
        assertFalse(limiter.tryAcquireAt(null, 1000));
    }

    /**
     * 把「多副本下这份实现不够用」写成用例，而不是只写在注释里。
     *
     * <p>两个实例各数一份，上限就从配置的 2 变成 4——这正是生产默认必须走 Redis 的原因（§2.3）。
     */
    @Test
    void 两个实例各数一份_合起来会放大限额() {
        InMemoryUserRateLimiter replicaA = new InMemoryUserRateLimiter(2);
        InMemoryUserRateLimiter replicaB = new InMemoryUserRateLimiter(2);

        assertTrue(replicaA.tryAcquireAt("alice", 1000));
        assertTrue(replicaA.tryAcquireAt("alice", 1000));
        assertFalse(replicaA.tryAcquireAt("alice", 1000));
        // 换个副本又拿到一整份额度：本地计数的代价，Redis 版（RedisUserRateLimiter）没有这个问题
        assertTrue(replicaB.tryAcquireAt("alice", 1000));
        // 但计时口径本身是对的：过了窗口各自都会重新放开
        assertTrue(replicaA.tryAcquireAt("alice", 2001));
    }
}