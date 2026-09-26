package com.djzy.assistant.gateway.core;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 多副本实现（§2.3 / §9.3）：计数在 Redis 里，所有网关实例共用同一份口径。
 *
 * <p>**键里带秒号**（{@code gateway:user:rate:<userId>:<epochSecond>}）——固定窗口，每秒一班，
 * 到点自然重开。为什么不用 Redis 滑动窗口：每次调用都要写一次再查一段区间，而这里要挡的只是
 * 「一秒内别刷太猛」，固定窗口的代价（窗口交界处最多放过两倍额度）完全可以接受。
 *
 * <p>**为什么用 Lua**：{@code INCR} 和「首个请求补 TTL」必须在一次往返里做完。
 * 分两条发的话，中间崩一次就留下一个永远不清理的计数键，这个用户的额度被永久占住。
 *
 * <p>**Redis 不可用时抛 {@link UserRateLimitUnavailableException}**：不静默放行（等于把限速整条丢掉），
 * 也不退回本地计数——那正是 §2.3 要根治的隐性单点。调用方把它映射成 503，而不是 429。
 */
public final class RedisUserRateLimiter implements UserRateLimiter {

    /** INCR 后按需设 TTL：只有第一个进窗口的请求设得上，后续重复设也无害（幂等）。 */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    static final String KEY_PREFIX = "gateway:user:rate:";
    private static final long WINDOW_MS = 1000L;

    private final StringRedisTemplate template;
    private final int permitsPerSecond;

    public RedisUserRateLimiter(StringRedisTemplate template, int permitsPerSecond) {
        this.template = template;
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    @Override
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long window = System.currentTimeMillis() / WINDOW_MS;
        // 多留一个窗口的余量：键过期只做清理，判定从不依赖它（计数以键里的秒号为准）
        Duration ttl = Duration.ofMillis(WINDOW_MS * 2);
        Long used;
        try {
            used = template.execute(
                    ACQUIRE, List.of(KEY_PREFIX + key + ":" + window), String.valueOf(ttl.toMillis()));
        } catch (RuntimeException e) {
            throw new UserRateLimitUnavailableException("用户限速计数（Redis）不可用，拒绝放行（§2.3 / §9.3）", e);
        }
        if (used == null) {
            throw new UserRateLimitUnavailableException("用户限速计数（Redis）没有返回计数，拒绝放行（§2.3）", null);
        }
        return used <= permitsPerSecond;
    }

    /** 计数存储不可用（fail-closed）：调用方不该把它和「超额」混成同一个响应。 */
    public static final class UserRateLimitUnavailableException extends RuntimeException {

        public UserRateLimitUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}