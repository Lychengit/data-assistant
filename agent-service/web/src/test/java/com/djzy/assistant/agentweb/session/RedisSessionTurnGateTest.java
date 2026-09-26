package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis 上的轮次闸门（T1-07）：这里要证的只有一件事——**两台实例抢同一把坑位时只有一台赢**。
 *
 * <p>本机没有 Redis 时跳过（{@code assumeTrue}），并且**跳过不等于通过**：
 * 内存实现（{@link InMemorySessionTurnGate}）只能证明单机语义，
 * 「跨实例」这件事必须落在真 Redis 上才验得到（{@code SET NX PX} 是不是原子的）。
 *
 * <p>槽位里存的是随机令牌，所以「谁在跑」不在这里断言——那个问题由 {@code platform_turn_live}
 * 回答（它记着轮次号与实例名），用例在 {@code AgentWebMultiInstanceTest} 里覆盖。
 */
class RedisSessionTurnGateTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);
    private static final Duration TTL = Duration.ofMinutes(15);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private RedisSessionTurnGate gate;
    private String sessionId;
    private String user;
    /** 用例占过的坑位：用例结束时统一放掉，免得污染同一台 Redis 上的其它用例。 */
    private final List<TurnLease> leases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        gate = new RedisSessionTurnGate(template, TTL);
        // 每个用例一把独立坑位键，互不干扰
        user = "gate-test";
        sessionId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        leases.forEach(TurnLease::close);
        leases.clear();
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void 两台实例抢同一把坑位只有一台赢() throws Exception {
        acquire(gate);

        assertThatThrownBy(() -> gate.acquire(key())).isInstanceOf(TurnBusyException.class);
    }

    @Test
    void 放坑之后别人马上能占上() throws Exception {
        TurnLease lease = acquire(gate);

        lease.close();
        assertThat(gate.isRunning(key())).isFalse();

        // 放掉之后同一个键能被重新占上（用户点完停止马上问下一轮的情形）
        assertThat(gate.acquire(key())).isNotNull();
    }

    @Test
    void 过期的旧券放不掉新一轮的坑_自己超时之后被接管的情形() throws Exception {
        RedisSessionTurnGate shortLived = new RedisSessionTurnGate(template, Duration.ofMillis(50));
        TurnLease stale = acquire(shortLived);
        sleep(120);
        TurnLease fresh = acquire(shortLived);

        // 旧的一轮姗姗来迟地收尾：券里的令牌已经不是当前坑位上的那个，删不掉新一轮的坑
        stale.close();
        assertThat(shortLived.isRunning(key())).isTrue();

        fresh.close();
        assertThat(shortLived.isRunning(key())).isFalse();
    }

    @Test
    void 坑位到点自己过期_实例被硬杀时不会把会话永久锁住() throws Exception {
        RedisSessionTurnGate shortLived = new RedisSessionTurnGate(template, Duration.ofMillis(50));
        acquire(shortLived);

        sleep(120);

        assertThat(shortLived.isRunning(key())).isFalse();
        assertThat(shortLived.acquire(key())).isNotNull();
    }

    private String key() {
        return TurnGateKeys.of(user, sessionId);
    }

    private TurnLease acquire(RedisSessionTurnGate target) throws TurnBusyException {
        TurnLease lease = target.acquire(key());
        leases.add(lease);
        return lease;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
