package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.bus.QueueRecord;
import com.djzy.assistant.common.bus.QueueStats;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** PG Outbox（§19.6）：入队幂等、拉取、确认、退避重试、死信。 */
class PgOutboxEventBusTest {

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
    void 入队幂等_重复投递不产生第二行() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgOutboxEventBus bus = new PgOutboxEventBus(jdbc, false);

        bus.publish(event("e-1", 1));
        bus.publish(event("e-1", 1));
        bus.publish(event("e-2", 2));

        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM event_outbox", Integer.class));
        assertEquals(2, bus.poll(10).size());
    }

    @Test
    void 事件本体原样往返_重启后不依赖进程内状态() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgOutboxEventBus bus = new PgOutboxEventBus(jdbc, false);
        bus.publish(event("e-1", 7));

        // 换一个实例读（模拟进程重启 / 另一个副本）：队列里存的是事件本体，不是指针
        QueueRecord record = new PgOutboxEventBus(jdbc, false).poll(10).get(0);
        assertEquals("e-1", record.eventId());
        assertEquals(AgentEventType.TEXT, record.event().type());
        assertEquals("t-1", record.event().turnId());
        assertEquals(7L, record.event().seq());
        assertEquals("已完成", record.event().payload().get("text"));
        assertEquals(0, record.attempts());
    }

    @Test
    void 确认后不再被拉取_水位归零() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgOutboxEventBus bus = new PgOutboxEventBus(jdbc, false);
        bus.publish(event("e-1", 1));

        List<QueueRecord> batch = bus.poll(10);
        assertEquals(1, batch.size());
        assertEquals(1L, bus.stats().pending());

        bus.ack(batch);

        assertEquals(0, bus.poll(10).size());
        QueueStats stats = bus.stats();
        assertEquals(0L, stats.pending());
        assertEquals(0L, stats.oldestPendingEpochMs());
        assertEquals(0L, stats.lagMs(System.currentTimeMillis()));
    }

    @Test
    void 投递失败走退避_退避期内不再被拉取而是留着重试() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgOutboxEventBus bus = new PgOutboxEventBus(jdbc, false);
        bus.publish(event("e-1", 1));

        List<QueueRecord> batch = bus.poll(10);
        bus.nack(batch, "写入事实表失败");

        // 不确认：还在队列里（事件不会因为一次失败就消失）
        assertEquals(1L, bus.stats().pending());
        // 但已退避，本轮不再重复拉取，避免失败时把队列打满
        assertEquals(0, bus.poll(10).size());
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE event_id = 'e-1'", Integer.class));
        assertEquals(
                "写入事实表失败",
                jdbc.queryForObject("SELECT last_error FROM event_outbox WHERE event_id = 'e-1'", String.class));
    }

    @Test
    void 超过上限进死信_出队且留下证据() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgOutboxEventBus bus = new PgOutboxEventBus(jdbc, false);
        bus.publish(event("e-1", 1));

        QueueRecord record = bus.poll(10).get(0);
        bus.deadLetter(record, "落库连续失败 5 次");

        assertEquals(0, bus.poll(10).size());
        assertEquals(0L, bus.stats().pending());
        assertEquals(1L, bus.stats().deadLettered());
        Map<String, Object> dead = jdbc.queryForMap("SELECT event_id, error, retry_count FROM event_dead_letter");
        assertEquals("e-1", dead.get("event_id"));
        assertEquals("落库连续失败 5 次", dead.get("error"));
        assertEquals(1, ((Number) dead.get("retry_count")).intValue());
        assertTrue(jdbc.queryForObject(
                        "SELECT delivered_at IS NOT NULL FROM event_outbox WHERE event_id = 'e-1'", Boolean.class)
                .booleanValue());
    }

    @Test
    void 按event_id排查_能看到还在不在队列里() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgOutboxEventBus bus = new PgOutboxEventBus(jdbc, false);
        bus.publish(event("e-1", 1));

        assertEquals(1, bus.find("e-1").size());
        assertEquals(0, bus.find("e-x").size());
    }
}