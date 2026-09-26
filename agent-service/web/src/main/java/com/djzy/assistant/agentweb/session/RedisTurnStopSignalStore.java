package com.djzy.assistant.agentweb.session;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 多副本用的停止信号（Redis 版）。
 *
 * <p>它解决的是「停止请求落到 A 实例、真正在跑的那一轮在 B 实例」这件事：
 * A 把信号写进 Redis，B 在推进事件时会读到它，然后取消自己的运行时。
 *
 * <p>用带 TTL 的普通键，不用 Pub/Sub：Pub/Sub 需要每个实例常驻订阅与断线重连，
 * 而这里要的只是「B 迟早会看到」——信号是幂等的，重复看到也不会有副作用。
 */
public final class RedisTurnStopSignalStore implements TurnStopSignalStore {

    /** 键前缀；多环境共用一个 Redis 时靠前缀 + 应用名区分。 */
    private static final String KEY_PREFIX = "agent:turn-stop:";

    /** 信号存活时长：一轮最多几分钟，10 分钟足够覆盖；过期由 Redis 自己回收，不留垃圾键。 */
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public RedisTurnStopSignalStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void request(String sessionId, String turnId) {
        redis.opsForValue().set(key(sessionId, turnId), "1", TTL);
    }

    @Override
    public boolean isRequested(String sessionId, String turnId) {
        return Boolean.TRUE.equals(redis.hasKey(key(sessionId, turnId)));
    }

    @Override
    public void clear(String sessionId, String turnId) {
        redis.delete(key(sessionId, turnId));
    }

    private static String key(String sessionId, String turnId) {
        return KEY_PREFIX + sessionId + ':' + turnId;
    }
}
