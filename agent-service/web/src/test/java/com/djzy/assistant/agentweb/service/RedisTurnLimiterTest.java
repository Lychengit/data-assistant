package com.djzy.assistant.agentweb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis 上的轮次配额（§9.3 / §2.3：计数必须跨实例一致）。
 *
 * <p>本机没有 Redis 时**跳过**（{@code assumeTrue}）：这不是「测试通过」，而是「没有可验证的环境」，
 * 不允许用内存实现冒名顶替——那样恰好验不到这里唯一要证的东西：多副本同口径。
 */
class RedisTurnLimiterTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private String user;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        // 每个用例一个独立用户 = 一个独立计数键，互不干扰
        user = "rate-test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (template != null) {
            try {
                Set<String> keys = template.keys("agent:turn:rate:" + user + ":*");
                if (keys != null && !keys.isEmpty()) {
                    template.delete(keys);
                }
            } catch (RuntimeException e) {
                // fail-closed 那条用例会主动把连接工厂拆掉，清理失败无所谓：键本来就有 TTL
            }
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    /** 这条用例就是本实现存在的理由：换一个副本问，额度也是同一份。 */
    @Test
    void 额度跨实例同口径() {
        TurnLimiter replicaA = new RedisTurnLimiter(template, 3);
        TurnLimiter replicaB = new RedisTurnLimiter(template, 3);

        assertThat(replicaA.tryAcquire(user)).isTrue();
        assertThat(replicaB.tryAcquire(user)).isTrue();
        assertThat(replicaA.tryAcquire(user)).isTrue();
        // 第 4 次无论在哪个副本上问，都该被挡下（本地内存计数会各放行 3 次）
        assertThat(replicaB.tryAcquire(user)).isFalse();
        assertThat(replicaA.tryAcquire(user)).isFalse();
    }

    @Test
    void 额度用尽后计数键带TTL_不会永久占住额度() {
        new RedisTurnLimiter(template, 1).tryAcquire(user);

        Set<String> keys = template.keys("agent:turn:rate:" + user + ":*");
        assertThat(keys).hasSize(1);
        Long ttlMs = template.getExpire(keys.iterator().next(), java.util.concurrent.TimeUnit.MILLISECONDS);
        // 键按窗口号过期：清理只靠 TTL，判定从不依赖它
        assertThat(ttlMs).isNotNull().isPositive().isLessThanOrEqualTo(120_000L);
    }

    @Test
    void 上限为零表示不限制() {
        TurnLimiter unlimited = new RedisTurnLimiter(template, 0);
        for (int i = 0; i < 50; i++) {
            assertThat(unlimited.tryAcquire(user)).isTrue();
        }
    }

    @Test
    void 计数存储掉线时拒绝放行_fail_closed() {
        TurnLimiter limiter = new RedisTurnLimiter(template, 10);
        factory.destroy();

        assertThatThrownBy(() -> limiter.tryAcquire(user))
                .isInstanceOf(RedisTurnLimiter.TurnQuotaUnavailableException.class);
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), (int) Duration.ofSeconds(1).toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}