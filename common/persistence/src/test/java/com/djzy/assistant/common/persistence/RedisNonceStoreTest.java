package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * 真实 Redis 上的防重放 nonce（§20.1.4-3）：{@code SET key value NX EX}，跨实例同一份台账。
 *
 * <p>本机没有 Redis 时**跳过**（{@code assumeTrue}）：这不是「测试通过」，而是「没有可验证的环境」——
 * 这里唯一要证的是「A 实例消费过的 nonce，B 实例也认得」，只有真 Redis 跑得出来。
 */
class RedisNonceStoreTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private String keyId;
    private String nonce;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        // 每个用例一组独立 keyId + nonce，互不干扰
        keyId = "test-key-" + UUID.randomUUID();
        nonce = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        if (template != null) {
            try {
                clearNonces();
            } catch (RuntimeException e) {
                // fail-closed 那条用例会主动把连接工厂拆掉，清理失败无所谓：键本来就有 TTL
            }
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    /** 这条用例就是本实现存在的理由：换一个实例问，也认得这个 nonce 已经用过。 */
    @Test
    void 同一个nonce只能消费一次_另一个实例也认得() {
        RedisNonceStore instanceA = new RedisNonceStore(template);
        RedisNonceStore instanceB = new RedisNonceStore(template);

        assertTrue(instanceA.tryConsume(keyId, nonce, Duration.ofSeconds(600)));
        // 重放：同一份台账，B 实例也必须说「用过了」
        assertFalse(instanceB.tryConsume(keyId, nonce, Duration.ofSeconds(600)));
        assertFalse(instanceA.tryConsume(keyId, nonce, Duration.ofSeconds(600)));
    }

    @Test
    void nonce按keyId分区_不同调用方互不影响() {
        RedisNonceStore store = new RedisNonceStore(template);

        assertTrue(store.tryConsume(keyId, nonce, Duration.ofSeconds(600)));
        assertTrue(store.tryConsume(keyId + "-other", nonce, Duration.ofSeconds(600)));
    }

    @Test
    void 键带TTL_过期后自动放开() {
        new RedisNonceStore(template).tryConsume(keyId, nonce, Duration.ofSeconds(600));

        Set<String> keys = template.keys(RedisNonceStore.KEY_PREFIX + keyId + ":" + nonce);
        assertNotNull(keys);
        assertTrue(keys.size() == 1, "应当只留下一条 nonce 键，实际 " + keys);
        Long ttlSeconds = template.getExpire(keys.iterator().next(), TimeUnit.SECONDS);
        // 时间窗一过就不再需要这条记录，靠 Redis 自己清理，不需要人工扫表
        assertNotNull(ttlSeconds);
        assertTrue(ttlSeconds > 0 && ttlSeconds <= 600L, "TTL 应当在 (0, 600] 秒之间，实际 " + ttlSeconds);
    }

    @Test
    void 空入参直接判重放() {
        RedisNonceStore store = new RedisNonceStore(template);

        assertFalse(store.tryConsume(null, nonce, Duration.ofSeconds(600)));
        assertFalse(store.tryConsume(keyId, " ", Duration.ofSeconds(600)));
    }

    /**
     * Redis 掉线时抛错，而不是返回 true。
     *
     * <p>调用方（{@code SignatureVerificationFilter}）没接这个异常，它会一路冒到容器变成 500，
     * 请求被拒——也就是 fail-closed。反过来若这里「拿不到结论就当没重放」，防重放会静默失效。
     */
    @Test
    void Redis掉线时抛出异常_不会静默放过重放() {
        RedisNonceStore store = new RedisNonceStore(template);
        factory.destroy();

        assertThrows(RuntimeException.class, () -> store.tryConsume(keyId, nonce, Duration.ofSeconds(600)));
    }

    /** 清掉这条用例留下的 nonce 键（顺带把连接建起来，省得第一条命令耗在握手上）。 */
    private void clearNonces() {
        Set<String> keys = template.keys(RedisNonceStore.KEY_PREFIX + keyId + "*");
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