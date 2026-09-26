package com.djzy.assistant.agentweb.session;

import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 多副本用的轮次闸门（Redis 版，T1-07），实现框架的 {@link SessionTurnGate}。
 *
 * <p>**为什么要跨副本互斥**：多副本下同一个会话的两次请求会被负载均衡丢到不同实例上。
 * 各实例只看自己的内存，都会觉得「没人跑」，于是两边同时起一轮、一起往同一份会话状态里写——
 * 结果是其中一轮的消息被另一轮覆盖（谁最后写谁赢），用户看到的是「我问过，agent 却像没听见」。
 * 这不是并发压力才会出现的问题，用户连点两下「发送」就能触发。
 *
 * <p>**为什么是「占坑 + TTL」而不是「加锁 + 解锁」**：进程被硬杀（OOM / SIGKILL）时没人会来解锁。
 * 带 TTL 的坑位到点自己消失，会话下一轮就能继续；代价是 TTL 内用户会看到「上一轮还在处理中」，
 * 所以 TTL 取「一轮的正常上限」而不是随便一个很长的值（见 {@code AgentServiceProperties.turnLease()}）。
 *
 * <p>**与状态库 CAS 的分工**：CAS 是**正确性底线**（真撞上了也不会互相覆盖），
 * 这道闸门是**让撞车不发生**（省掉一轮白跑的模型调用，也避免用户白等）。两者都要。
 *
 * <p>**坑位里存的是随机令牌而不是「哪一轮」**：「谁在跑」这个问题由 {@code platform_turn_live}
 * 回答（那里记着轮次号、实例名、用户的原话），这里再存一份就成了第二个真相。
 */
public final class RedisSessionTurnGate implements SessionTurnGate {

    private static final String KEY_PREFIX = "agent:turn-gate:";

    /** 只放自己的坑：值不匹配（坑位已经被下一轮接管）就什么都不做。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration leaseTtl;

    public RedisSessionTurnGate(StringRedisTemplate redis, Duration leaseTtl) {
        this.redis = redis;
        this.leaseTtl = leaseTtl;
    }

    /**
     * 占坑是一条原子命令 {@code SET key value NX PX ttl}：**只有一台**实例能成功，
     * 别的实例当场就知道「有人在跑」。改成「先 GET 再 SET」的话，两台实例会在同一毫秒里
     * 同时看到「没人占」并各自占上——闸门就形同虚设。
     */
    @Override
    public TurnLease acquire(String key) throws TurnBusyException {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey(key), token, leaseTtl);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new TurnBusyException(key);
        }
        return () -> releaseOwned(redisKey(key), token);
    }

    @Override
    public boolean isRunning(String key) {
        return Boolean.TRUE.equals(redis.hasKey(redisKey(key)));
    }

    /** 放坑用一段 Lua「比对再删」：自己超时、坑位被下一轮接管之后，这次收尾不能把**别人的**坑放掉。 */
    private void releaseOwned(String redisKey, String token) {
        redis.execute(RELEASE_SCRIPT, List.of(redisKey), token);
    }

    private static String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
