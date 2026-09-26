package com.djzy.assistant.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis 上的用户级限速（§2.3 / §9.3：计数必须跨实例一致）。
 *
 * <p>本机没有 Redis 时**跳过**（{@code assumeTrue}）：这不是「测试通过」，而是「没有可验证的环境」，
 * 不允许用内存实现冒名顶替——那样恰好验不到这里唯一要证的东西：多副本同口径。
 */
class RedisUserRateLimiterTest {

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
        user = "gw-rate-test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (template != null) {
            try {
                clearCounters();
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
        UserRateLimiter replicaA = new RedisUserRateLimiter(template, 3);
        UserRateLimiter replicaB = new RedisUserRateLimiter(template, 3);

        // 窗口按整秒切，"3 次放行、第 4 次挡下"只有整串断言都落在同一个窗口里才成立。
        // 所以这里不用 sleep 去赌时间，而是「窗口内断言 + 一旦跨秒就清空重来」——结果与机器快慢无关。
        for (int attempt = 0; attempt < 10; attempt++) {
            clearCounters();
            long window = System.currentTimeMillis() / 1000L;

            boolean first = replicaA.tryAcquire(user);
            boolean second = replicaB.tryAcquire(user);
            boolean third = replicaA.tryAcquire(user);
            boolean fourth = replicaB.tryAcquire(user);
            boolean fifth = replicaA.tryAcquire(user);

            if (System.currentTimeMillis() / 1000L != window) {
                continue; // 中途跨秒：这一轮的计数已经换了窗口，重来
            }
            assertThat(first).isTrue();
            assertThat(second).isTrue();
            assertThat(third).isTrue();
            // 第 4、5 次无论在哪个副本上问，都该被挡下（本地内存计数会各放行 3 次）
            assertThat(fourth).isFalse();
            assertThat(fifth).isFalse();
            return;
        }
        throw new AssertionError("连试十次都没能在一秒窗口内跑完这串断言，本机负载异常");
    }

    @Test
    void 计数键带TTL_不会永久占住额度() {
        new RedisUserRateLimiter(template, 1).tryAcquire(user);

        Set<String> keys = template.keys(RedisUserRateLimiter.KEY_PREFIX + user + ":*");
        assertThat(keys).hasSize(1);
        Long ttlMs = template.getExpire(keys.iterator().next(), TimeUnit.MILLISECONDS);
        // 键按窗口号过期：清理只靠 TTL，判定从不依赖它
        assertThat(ttlMs).isNotNull().isPositive().isLessThanOrEqualTo(2000L);
    }

    @Test
    void 空用户直接拒绝() {
        assertThat(new RedisUserRateLimiter(template, 5).tryAcquire(" ")).isFalse();
        assertThat(new RedisUserRateLimiter(template, 5).tryAcquire(null)).isFalse();
    }

    @Test
    void 计数存储掉线时拒绝放行_fail_closed() {
        UserRateLimiter limiter = new RedisUserRateLimiter(template, 10);
        factory.destroy();

        assertThatThrownBy(() -> limiter.tryAcquire(user))
                .isInstanceOf(RedisUserRateLimiter.UserRateLimitUnavailableException.class);
    }

    /** 清掉这条用例留下的计数键（顺带把连接建起来，省得第一条命令耗在握手上）。 */
    private void clearCounters() {
        Set<String> keys = template.keys(RedisUserRateLimiter.KEY_PREFIX + user + ":*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
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