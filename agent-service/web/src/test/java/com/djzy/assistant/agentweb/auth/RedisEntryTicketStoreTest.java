package com.djzy.assistant.agentweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis 上的入场券（§19.4 / §20.1.4 同款一次性语义）。
 *
 * <p>本机没有 Redis 时**跳过**（{@code assumeTrue}）：这不是「测试通过」，而是「没有可验证的环境」，
 * 不允许用内存实现冒名顶替。
 */
class RedisEntryTicketStoreTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private RedisEntryTicketStore store;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        store = new RedisEntryTicketStore(template);
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void 券只存哈希且只能消费一次() {
        String hash = "hash-" + java.util.UUID.randomUUID();
        EntryTicket ticket = new EntryTicket("alice", "s1", "t1", Instant.now().plusSeconds(60));

        assertThat(store.saveIfAbsent(hash, ticket, 60)).isTrue();

        assertThat(store.consume(hash, Instant.now())).contains(ticket);
        assertThat(store.consume(hash, Instant.now())).isEmpty();
    }

    @Test
    void 同一个键存第二次必须失败且不覆盖第一张() {
        String hash = "hash-" + java.util.UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);
        EntryTicket alice = new EntryTicket("alice", "s1", "t1", expiresAt);

        assertThat(store.saveIfAbsent(hash, alice, 60)).isTrue();
        // 撞号：SET NX 必须拒掉第二次写入。若它覆盖成功，alice 的券就会查出 bob 的身份（串号）
        assertThat(store.saveIfAbsent(hash, new EntryTicket("bob", "s2", "t2", expiresAt), 60))
                .isFalse();

        assertThat(store.consume(hash, Instant.now())).contains(alice);
    }

    @Test
    void 过期的券等于没有() {
        String hash = "hash-" + java.util.UUID.randomUUID();
        store.saveIfAbsent(hash, new EntryTicket("alice", "s1", "t1", Instant.now().minusSeconds(1)), 60);

        assertThat(store.consume(hash, Instant.now())).isEmpty();
    }

    @Test
    void 存储掉线时拒绝放行() {
        String hash = "hash-" + java.util.UUID.randomUUID();
        store.saveIfAbsent(hash, new EntryTicket("alice", "s1", "t1", Instant.now().plusSeconds(60)), 60);
        factory.destroy();

        assertThatThrownBy(() -> store.consume(hash, Instant.now()))
                .isInstanceOf(EntryTicketStore.UnavailableException.class);
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
