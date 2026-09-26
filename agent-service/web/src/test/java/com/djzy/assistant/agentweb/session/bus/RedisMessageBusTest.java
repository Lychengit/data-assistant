package com.djzy.assistant.agentweb.session.bus;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.bus.BusEntry;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 共享总线的真 Redis 实现（H-01）：这里要证的是「跨实例真的读写得到同一份」。
 *
 * <p>本机没有 Redis 时跳过（{@code assumeTrue}），并且**跳过不等于通过**：内存实现
 * （{@link InMemoryMessageBus}）只能证明单进程语义。「换一台实例接着看」这件事
 * 必须落在真 Redis 上才验得到——键怎么拼、TTL 怎么续、条目号跨实例唯不唯一，都在这里。
 *
 * <p>每个用例用一把独立前缀：同一台 Redis 上可能还有别的用例在跑（轮次闸门、限流、券都是）。
 */
class RedisMessageBusTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private RedisMessageBus bus;
    private String prefix;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        prefix = "test:bus:" + UUID.randomUUID() + ":";
        bus = new RedisMessageBus(template, factory, prefix, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void 写进去的会话事件能按游标不重不漏地读回来() {
        String slot = "session:events:alice:s-1";
        bus.sessionPublishEvent(slot, Map.of("turnId", "t1", "event", "token", "data", Map.of("delta", "你")))
                .block();
        bus.sessionPublishEvent(slot, Map.of("turnId", "t1", "event", "token", "data", Map.of("delta", "好")))
                .block();

        List<BusEntry> all = bus.sessionReadEvents(slot, "", 100).block();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).payload()).containsEntry("event", "token");
        assertThat(all.get(0).entryId()).isLessThan(all.get(1).entryId());
        // 带上最后一条的条目号再读：不该再读到东西（游标就是「我读到哪儿了」）
        assertThat(bus.sessionReadEvents(slot, all.get(1).entryId(), 100).block()).isEmpty();
    }

    @Test
    void 日志超过上限时最老的条目会被裁掉() {
        String log = "session:events:bob:s-2";
        for (int n = 1; n <= 5; n++) {
            bus.logAppend(log, Map.of("n", n), 3).block();
        }

        List<BusEntry> kept = bus.logRead(log, "", 100).block();

        assertThat(kept).extracting(entry -> entry.payload().get("n")).containsExactly(3, 4, 5);
    }

    @Test
    void 两台实例之间能靠订阅收到推送() throws Exception {
        String channel = "session:events:carol:s-3";
        // 第二个「实例」：同一个 Redis，另一份总线对象
        RedisMessageBus other = new RedisMessageBus(template, factory, prefix, Duration.ofMinutes(5));
        try {
            CompletableFuture<Map<String, Object>> firstMessage =
                    other.subscribe(channel).next().toFuture();
            // 订阅是异步建立的（Redis 的 SUBSCRIBE 要一个来回），所以这里连发几次，
            // 收到任意一次即算通过——用例要证的是「推送通不通」，不是「第几条到」
            for (int attempt = 0; attempt < 20 && !firstMessage.isDone(); attempt++) {
                sleep(100);
                bus.publish(channel, Map.of("n", attempt)).block();
            }

            assertThat(firstMessage.get(5, TimeUnit.SECONDS)).containsKey("n");
        } finally {
            other.close();
        }
    }

    /** 本机有没有 Redis：没有就跳过，而不是判失败（见类注释）。 */
    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
