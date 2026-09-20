package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.bus.QueueRecord;
import com.djzy.assistant.common.bus.QueueStats;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Redis Stream 队列（§19.6 / ADR-28）：XADD 投递、XREADGROUP 消费、XPENDING 退避重投、死信。
 *
 * <p>本机没有 Redis 时**跳过**（{@code assumeTrue}）：这不是「测试通过」，而是「没有可验证的环境」——
 * 退避判据、投递计数、待确认集合这些行为**只有真 Redis 跑得出来**，内存假的 Redis 只会把假设当结论
 * （与 {@code RedisEntryTicketStoreTest} 同一条纪律）。
 *
 * <p>每个用例一个独立的流名：Stream 是全局命名空间，用例之间串流会让「谁该收到几条」变成玄学。
 * 流名与消费组用完即删，免得在开发机的 Redis 里越攒越多。
 */
class RedisStreamEventQueueTest {

    private static final String HOST = System.getProperty("test.redis.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("test.redis.port", 6379);

    /** 退避窗口：attempts=1 时等 2s（2^n 秒），attempts=0 时等 1s。 */
    private static final long BACKOFF_AFTER_FIRST_FAILURE_MS = 2_100L;
    private static final long BACKOFF_FOR_NEVER_FAILED_MS = 1_200L;

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private DataSource dataSource;
    private String streamKey;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "本机没有 Redis（" + HOST + ":" + PORT + "），跳过真实 Redis 用例");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        dataSource = PersistenceTestSupport.dataSource();
        streamKey = "test:events:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (template != null && streamKey != null) {
            template.delete(streamKey);
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    private RedisStreamEventQueue queue(String consumerName) {
        return new RedisStreamEventQueue(template, dataSource, streamKey, consumerName, 10_000L, false);
    }

    private static AgentEvent event(String id, long seq) {
        return AgentEvent.builder(AgentEventType.TEXT)
                .eventId(id)
                .session("s-1")
                .turn("t-1")
                .trace("tr-1")
                .seq(seq)
                .put("text", "已完成")
                .build();
    }

    @Test
    void 投递与拉取_事件本体原样往返() {
        RedisStreamEventQueue queue = queue("c-1");
        queue.publish(event("e-1", 7));

        // 换一个实例读（模拟进程重启 / 另一个副本）：流里存的是事件本体，不是指针
        List<QueueRecord> batch = queue("c-2").poll(10);

        assertEquals(1, batch.size());
        QueueRecord record = batch.get(0);
        assertEquals("e-1", record.eventId());
        assertEquals(AgentEventType.TEXT, record.event().type());
        assertEquals("t-1", record.event().turnId());
        assertEquals(7L, record.event().seq());
        assertEquals("已完成", record.event().payload().get("text"));
        assertEquals(0, record.attempts());
    }

    @Test
    void 确认后不再被拉取_水位归零() {
        RedisStreamEventQueue queue = queue("c-1");
        queue.publish(event("e-1", 1));

        List<QueueRecord> batch = queue.poll(10);
        assertEquals(1, batch.size());
        assertEquals(1L, queue.stats().pending());
        assertTrue(queue.stats().oldestPendingEpochMs() > 0L, "最老待确认条目的入队时间取自消息 id");

        queue.ack(batch);

        assertEquals(0, queue.poll(10).size());
        QueueStats stats = queue.stats();
        assertEquals(0L, stats.pending());
        assertEquals(0L, stats.oldestPendingEpochMs());
        assertEquals(0L, stats.lagMs(System.currentTimeMillis()));
    }

    @Test
    void 投递失败不确认_退避到期后才重投() {
        RedisStreamEventQueue queue = queue("c-1");
        queue.publish(event("e-1", 1));

        List<QueueRecord> batch = queue.poll(10);
        assertEquals(1, batch.size());
        queue.nack(batch, "写入事实表失败");

        // 不确认：还在待确认集合里（事件不会因为一次失败就消失）
        assertEquals(1L, queue.stats().pending());
        // 但已退避：重投要等 2^n 秒，不是立刻忙等重试
        assertEquals(0, queue.poll(10).size());

        sleep(BACKOFF_AFTER_FIRST_FAILURE_MS);

        List<QueueRecord> retried = queue.poll(10);
        assertEquals(1, retried.size());
        assertEquals("e-1", retried.get(0).eventId());
        assertEquals(1, retried.get(0).attempts(), "attempts 必须严格等于失败次数，认领本身不算失败");
    }

    @Test
    void 未确认条目能被另一个副本接走() {
        RedisStreamEventQueue first = queue("c-1");
        first.publish(event("e-1", 1));
        assertEquals(1, first.poll(10).size());
        // 第一个副本拿到就「崩」了：既不确认也不退避，条目留在待确认集合里由空闲时长兜底

        assertEquals(0, queue("c-2").poll(10).size(), "刚投递过的条目还在退避窗口内，不能被立刻抢走");

        sleep(BACKOFF_FOR_NEVER_FAILED_MS);

        List<QueueRecord> takenOver = queue("c-2").poll(10);
        assertEquals(1, takenOver.size());
        assertEquals("e-1", takenOver.get(0).eventId());
    }

    @Test
    void 超过上限进死信_出队且留下证据() {
        RedisStreamEventQueue queue = queue("c-1");
        queue.publish(event("e-1", 1));

        QueueRecord record = queue.poll(10).get(0);
        queue.deadLetter(record, "落库连续失败 5 次");

        assertEquals(0, queue.poll(10).size());
        QueueStats stats = queue.stats();
        assertEquals(0L, stats.pending(), "死信必须出队，否则会无限重投（§19.6）");
        assertEquals(1L, stats.deadLettered());

        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        Map<String, Object> dead = jdbc.queryForMap("SELECT event_id, error, retry_count FROM event_dead_letter");
        assertEquals("e-1", dead.get("event_id"));
        assertEquals("落库连续失败 5 次", dead.get("error"));
        assertEquals(1, ((Number) dead.get("retry_count")).intValue());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待退避窗口时被中断", e);
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