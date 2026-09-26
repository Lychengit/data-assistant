package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.djzy.assistant.agentweb.session.bus.InMemoryMessageBus;
import com.djzy.assistant.agentweb.session.bus.RedisMessageBus;
import io.agentscope.harness.agent.bus.MessageBus;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 停止推送口（H-09）的守护用例。
 *
 * <p>分两层：
 * <ul>
 *   <li>**内存总线**那一层不需要任何外部依赖，验的是「推一条 → 订阅者收到」以及几条不该出事的边界；</li>
 *   <li>**真 Redis** 那一层才是这一项的意义所在：两台实例连着同一个 Redis，A 推的停止 B 收得到。
 *       本机没有 Redis 时跳过（{@code assumeTrue}）——跳过不等于通过，这一条只能在真 Redis 上验。</li>
 * </ul>
 */
class TurnStopChannelTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);

    private LettuceConnectionFactory factory;
    private String prefix;
    private final List<TurnStopChannel> opened = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        prefix = "test:stop:" + UUID.randomUUID() + ":";
    }

    @AfterEach
    void tearDown() {
        opened.forEach(TurnStopChannel::close);
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void 内存总线上推一条停止订阅者就收得到() {
        TurnStopChannel channel = open(new InMemoryMessageBus());
        List<String> received = new CopyOnWriteArrayList<>();
        channel.subscribe((userId, sessionId, turnId) -> received.add(userId + "/" + sessionId + "/" + turnId));

        channel.publish("alice", "s-1", "t-1");

        assertThat(received).containsExactly("alice/s-1/t-1");
    }

    @Test
    void 字段不全的推送被忽略且不抛异常() {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        TurnStopChannel channel = open(bus);
        List<String> received = new CopyOnWriteArrayList<>();
        channel.subscribe((userId, sessionId, turnId) -> received.add(turnId));

        // 直接往频道上塞一条缺字段的消息：等价于「别的版本 / 别的系统往这个频道发了不该发的东西」
        bus.publish("agent-service:turn-stop", Map.of("turnId", "t-1"));

        assertThat(received).isEmpty();
    }

    @Test
    void 订阅者抛异常也不会把推送方带崩() {
        TurnStopChannel channel = open(new InMemoryMessageBus());
        channel.subscribe((userId, sessionId, turnId) -> {
            throw new IllegalStateException("订阅者自己炸了");
        });

        assertThatCode(() -> channel.publish("alice", "s-1", "t-1")).doesNotThrowAnyException();
    }

    @Test
    void 两个实例共用一个Redis时A推的停止B收得到() throws Exception {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        // 两台「实例」用同一条 Redis、同一个前缀（真实部署里两台机器连的就是同一个 Redis），
        // 但各自有自己的推送口——订阅是每个实例各自做的事。
        TurnStopChannel instanceA = open(new RedisMessageBus(template, factory, prefix, Duration.ofMinutes(5)));
        TurnStopChannel instanceB = open(new RedisMessageBus(template, factory, prefix, Duration.ofMinutes(5)));

        CountDownLatch gotIt = new CountDownLatch(1);
        List<String> seenByB = new CopyOnWriteArrayList<>();
        instanceB.subscribe((userId, sessionId, turnId) -> {
            seenByB.add(userId + "/" + sessionId + "/" + turnId);
            gotIt.countDown();
        });
        // A 也订阅：真实部署里每台实例都订阅，A 推的东西 A 自己也会收到（收不收看订阅时机）
        instanceA.subscribe((userId, sessionId, turnId) -> {});

        instanceA.publish("alice", "s-1", "t-1");

        assertThat(gotIt.await(5, TimeUnit.SECONDS))
                .as("A 实例推的停止，B 实例应该在几秒内收到（推送链路不通就会超时）")
                .isTrue();
        assertThat(seenByB).containsExactly("alice/s-1/t-1");
    }

    /** 建一个推送口并记下来（用例结束时统一关掉）。 */
    private TurnStopChannel open(MessageBus bus) {
        TurnStopChannel channel = new TurnStopChannel(bus);
        opened.add(channel);
        return channel;
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
