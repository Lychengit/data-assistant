package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 轮次闸门的内存实现（T1-07）：语义要能在单机上完整验证，多副本口径由
 * {@link RedisSessionTurnGateTest} 对着真 Redis 验。
 *
 * <p>两条语义是**正确性**、不是体验，所以逐条钉住：
 * ① 同一会话同时只有一个持有者；② 只有持有者能放坑（别的实例的收尾不能把这一轮放掉）。
 * 第二条最容易被写成「按 turnId 删就行」——线上表现是「A 实例点了停止，B 实例上正在跑的那一轮
 * 被放掉了坑位，同一个会话立刻又能起一轮」，于是两轮并发写同一份会话状态。
 */
class InMemorySessionTurnGateTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final InMemorySessionTurnGate gate = new InMemorySessionTurnGate(TTL);

    @Test
    void 同一个会话同时只有一个持有者() throws Exception {
        gate.acquire("u1:s1");

        assertThatThrownBy(() -> gate.acquire("u1:s1")).isInstanceOf(TurnBusyException.class);
        assertThat(gate.isRunning("u1:s1")).isTrue();
    }

    @Test
    void 不同会话互不影响() throws Exception {
        gate.acquire("u1:s1");
        gate.acquire("u1:s2");
        gate.acquire("u2:s1");

        assertThat(gate.isRunning("u1:s1")).isTrue();
        assertThat(gate.isRunning("u1:s2")).isTrue();
        assertThat(gate.isRunning("u2:s1")).isTrue();
    }

    @Test
    void 放坑之后坑位就空出来了() throws Exception {
        TurnLease lease = gate.acquire("u1:s1");

        lease.close();

        assertThat(gate.isRunning("u1:s1")).isFalse();
    }

    @Test
    void 同一张券重复close是安全的() throws Exception {
        TurnLease lease = gate.acquire("u1:s1");

        lease.close();
        lease.close();

        assertThat(gate.isRunning("u1:s1")).isFalse();
    }

    @Test
    void 过期的旧券放不掉新一轮的坑_自己超时之后被接管的情形() throws Exception {
        InMemorySessionTurnGate shortLived = new InMemorySessionTurnGate(Duration.ofMillis(30));
        TurnLease stale = shortLived.acquire("u1:s1");
        sleep(60);
        TurnLease fresh = shortLived.acquire("u1:s1");

        // 旧的那一轮姗姗来迟地收尾：它手里的券已经不作数了，不能把新一轮的坑放掉
        stale.close();
        assertThat(shortLived.isRunning("u1:s1")).isTrue();

        fresh.close();
        assertThat(shortLived.isRunning("u1:s1")).isFalse();
    }

    @Test
    void 坑位到点自己过期_实例被硬杀时不会把会话永久锁住() throws Exception {
        InMemorySessionTurnGate shortLived = new InMemorySessionTurnGate(Duration.ZERO);
        shortLived.acquire("u1:s1");

        assertThat(shortLived.isRunning("u1:s1")).isFalse();
        // 过期之后别人能占上：这正是「实例被硬杀，会话不会永久锁死」的依据
        assertThat(shortLived.acquire("u1:s1")).isNotNull();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
