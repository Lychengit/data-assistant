package com.djzy.assistant.agentweb.session;

import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例用的轮次闸门（内存版，T1-07），实现框架的 {@link SessionTurnGate}。
 *
 * <p>**为什么不用框架自带的 {@code LocalSessionTurnGate}**：它是「公平信号量 + 排队等」——
 * 抢不到就在那里阻塞等待。但这里挡的是 HTTP / SSE 请求，而坑位租期是按「整轮流超时」算的，
 * 把用户的连接挂在那里等几分钟不响一声，比直接回一句「上一轮还在处理中」糟糕得多。
 * 所以这里保留「抢不到就当场说不行」：语义是**跳过**，不是排队——这也正是框架对
 * {@link TurnBusyException} 的定义（网关收到它就跳过这次唤醒，而不是把请求排进队列）。
 *
 * <p>只适用于**单副本**：它只拦得住打到同一台实例上的并发请求。多副本必须用
 * {@link RedisSessionTurnGate}（见 application.yml 的说明），否则「同一个会话同时跑两轮」会静默发生。
 */
public final class InMemorySessionTurnGate implements SessionTurnGate {

    /** 一个坑位：谁占的（令牌）、什么时候到期。 */
    private record Lease(String token, long expiresAtMs) {}

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final Duration leaseTtl;

    public InMemorySessionTurnGate(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
    }

    @Override
    public TurnLease acquire(String key) throws TurnBusyException {
        long now = System.currentTimeMillis();
        Lease fresh = new Lease(UUID.randomUUID().toString(), now + leaseTtl.toMillis());
        // compute 是原子的：同一把键上并发调用只有一个能把值放进去（已过期的旧坑位可以被顶掉）
        Lease[] occupied = new Lease[1];
        leases.compute(key, (ignored, current) -> {
            if (current != null && current.expiresAtMs() > now) {
                occupied[0] = current;
                return current;
            }
            return fresh;
        });
        if (occupied[0] != null) {
            throw new TurnBusyException(key);
        }
        return () -> releaseOwned(key, fresh.token());
    }

    @Override
    public boolean isRunning(String key) {
        Lease lease = leases.get(key);
        if (lease == null) {
            return false;
        }
        // 到点即过期（用 <= 而不是 <：租期为 0 的坑位不该被当成还占着）
        if (lease.expiresAtMs() <= System.currentTimeMillis()) {
            leases.remove(key, lease);
            return false;
        }
        return true;
    }

    /**
     * 放掉坑位，**只有持有者能放**。
     *
     * <p>为什么必须比对令牌：停止请求可能落到**别的实例**上，那台实例不该替持有者放坑——
     * 它一放，这个会话立刻就能起新一轮，而旧的那一轮还在跑，两轮并发写入的正是我们要防的事。
     * 同一条规则也顺手覆盖了「自己超时之后坑位被下一轮接管」的情况。
     */
    private void releaseOwned(String key, String token) {
        leases.computeIfPresent(key, (ignored, current) -> token.equals(current.token()) ? null : current);
    }
}
