package com.djzy.assistant.agentweb.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 多副本实现（§9.3 / §2.3）：计数在 Redis 里，所有副本同一份口径。
 *
 * <p>**为什么不用「本地计数 + 定期同步」**：多副本各算一份，用户换个副本就等于又拿到一整份额度，
 * 口径失真且随副本数放大；规格直接禁止本地内存计数（§2.3 硬约束第 8 条）。
 *
 * <p>**窗口口径**：键里带分钟号（{@code agent:turn:rate:<userId>:<window>}），是**固定窗口**——
 * 每分钟一班，到点自然重开。滑动窗口要么用 ZSET 存每次调用的时刻（每轮一次写 + 一次范围查询，
 * 骨架期不值当），要么用 Lua 维护「上一窗口 + 当前窗口」两个计数（口径更绕）。
 * 固定窗口的代价是窗口交界处最多放过两倍额度，对「用户每分钟问答轮次」这种量级完全够用。
 *
 * <p>**为什么用 Lua**：{@code INCR} 与 {@code 首次 PEXPIRE} 必须在一次往返里做完。
 * 分两条发的话，中间崩一次就留下一个**永不清理**的计数键（这个用户的额度被永久占住）。
 *
 * <p>**Redis 不可用时抛异常（fail-closed）**：宁可这一轮报「服务暂不可用」，
 * 也不许静默退回本地计数——那正是 §2.3 要根治的隐性单点。
 */
public final class RedisTurnLimiter implements TurnLimiter {

    /** INCR 后按需设 TTL：只有第一个进窗口的请求设得上，后续重复设也无害（幂等）。 */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private static final String KEY_PREFIX = "agent:turn:rate:";
    private static final long WINDOW_MS = 60_000L;

    private final StringRedisTemplate template;
    private final int permitsPerWindow;
    /** 观测用：本实例碰过的用户 → 本窗口已用额度。 */
    private final ConcurrentMap<String, Integer> observed = new ConcurrentHashMap<>();

    public RedisTurnLimiter(StringRedisTemplate template, int permitsPerWindow) {
        this.template = template;
        this.permitsPerWindow = permitsPerWindow <= 0 ? Integer.MAX_VALUE : permitsPerWindow;
    }

    @Override
    public boolean tryAcquire(String userId) {
        if (userId == null || permitsPerWindow == Integer.MAX_VALUE) {
            return true;
        }
        long window = System.currentTimeMillis() / WINDOW_MS;
        // 多留一个窗口的余量：键过期只做清理，判定从不依赖它（计数以键里的窗口号为准）
        Duration ttl = Duration.ofMillis(WINDOW_MS * 2);
        Long used;
        try {
            used = template.execute(ACQUIRE, List.of(key(userId, window)), String.valueOf(ttl.toMillis()));
        } catch (RuntimeException e) {
            throw new TurnQuotaUnavailableException("轮次配额存储（Redis）不可用，拒绝放行（§2.3 / §9.3）", e);
        }
        if (used == null) {
            throw new TurnQuotaUnavailableException("轮次配额存储（Redis）没有返回计数，拒绝放行（§2.3）", null);
        }
        observed.put(userId, used.intValue());
        return used <= permitsPerWindow;
    }

    @Override
    public Map<String, Integer> windows() {
        return Map.copyOf(observed);
    }

    private static String key(String userId, long window) {
        return KEY_PREFIX + userId + ":" + window;
    }

    /** 计数存储不可用（fail-closed）：调用方不该把它和「超额」混成同一个响应。 */
    public static final class TurnQuotaUnavailableException extends RuntimeException {

        TurnQuotaUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}