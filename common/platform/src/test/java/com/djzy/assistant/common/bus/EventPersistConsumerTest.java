package com.djzy.assistant.common.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** event-persist 消费者（§19.6）：攒批节奏、确认、退避重试、超限进死信。 */
class EventPersistConsumerTest {

    private static AgentEvent event(String id) {
        return AgentEvent.builder(AgentEventType.TEXT)
                .eventId(id)
                .session("s-1")
                .turn("t-1")
                .trace("tr-1")
                .seq(1)
                .put("text", "已完成")
                .build();
    }

    @Test
    void 攒够一批就落库并确认() {
        FakeQueue queue = new FakeQueue();
        for (int i = 0; i < EventPersistConsumer.DEFAULT_BATCH_SIZE; i++) {
            queue.publish(event("e-" + i));
        }
        RecordingWriter writer = new RecordingWriter();

        int rows = new EventPersistConsumer(queue, writer, EventQueueMetrics.noop()).drainOnce();

        assertEquals(EventPersistConsumer.DEFAULT_BATCH_SIZE, rows);
        assertEquals(List.of(EventPersistConsumer.DEFAULT_BATCH_SIZE), writer.batchSizes);
        assertEquals(EventPersistConsumer.DEFAULT_BATCH_SIZE, queue.acked.size());
        assertEquals(0, queue.records.size());
        assertEquals(0, queue.nacks, "落库成功不该有退避");
    }

    @Test
    void 单批不超过上限_剩下的留给下一轮() {
        FakeQueue queue = new FakeQueue();
        int total = EventPersistConsumer.DEFAULT_MAX_BATCH + 20;
        for (int i = 0; i < total; i++) {
            queue.publish(event("e-" + i));
        }
        RecordingWriter writer = new RecordingWriter();
        EventPersistConsumer consumer = new EventPersistConsumer(queue, writer, EventQueueMetrics.noop());

        assertEquals(EventPersistConsumer.DEFAULT_MAX_BATCH, consumer.drainOnce());
        assertEquals(20, consumer.drainOnce());
        assertEquals(List.of(EventPersistConsumer.DEFAULT_MAX_BATCH, 20), writer.batchSizes);
    }

    @Test
    void 队列空时不写不确认_但照样报水位() {
        FakeQueue queue = new FakeQueue();
        RecordingWriter writer = new RecordingWriter();
        RecordingMetrics metrics = new RecordingMetrics();

        assertEquals(0, new EventPersistConsumer(queue, writer, metrics).drainOnce());
        assertTrue(writer.batchSizes.isEmpty());
        assertEquals(1, metrics.backlogs.size(), "每轮都要报一次水位，队列空也要报（否则积压会静默）");
    }

    @Test
    void 落库失败不确认_退避重试而不是丢掉() {
        FakeQueue queue = new FakeQueue();
        queue.publish(event("e-1"));
        queue.publish(event("e-2"));
        RecordingWriter writer = new RecordingWriter();
        writer.failWith = new IllegalStateException("事实表写不进去");
        RecordingMetrics metrics = new RecordingMetrics();

        assertEquals(0, new EventPersistConsumer(queue, writer, metrics).drainOnce());

        assertEquals(0, queue.acked.size(), "没写成功就不能确认");
        assertEquals(2, queue.records.size(), "事件一条都不能消失");
        assertEquals(2, queue.nacks);
        assertEquals(1, metrics.consumeFailures);
        assertTrue(queue.deadLettered.isEmpty(), "第一次失败还不到上限，不该进死信");
    }

    @Test
    void 失败到上限进死信_不无限重投() {
        FakeQueue queue = new FakeQueue();
        // attempts 已经是 4：这一次再失败就到上限 5
        queue.records.add(new QueueRecord("r-1", event("e-1"), 4));
        RecordingWriter writer = new RecordingWriter();
        writer.failWith = new IllegalStateException("事实表写不进去");
        RecordingMetrics metrics = new RecordingMetrics();

        assertEquals(0, new EventPersistConsumer(queue, writer, metrics).drainOnce());

        assertEquals(List.of("e-1"), queue.deadLettered);
        assertEquals(1, metrics.deadLetters.size());
        assertEquals(0, queue.nacks, "已经进死信了就不该再退避重试");
    }

    @Test
    void 混批时只把到上限的那条送死信_其余继续重试() {
        FakeQueue queue = new FakeQueue();
        queue.records.add(new QueueRecord("r-1", event("e-1"), 4));
        queue.records.add(new QueueRecord("r-2", event("e-2"), 0));
        RecordingWriter writer = new RecordingWriter();
        writer.failWith = new IllegalStateException("事实表写不进去");
        RecordingMetrics metrics = new RecordingMetrics();

        new EventPersistConsumer(queue, writer, metrics).drainOnce();

        assertEquals(List.of("e-1"), queue.deadLettered);
        assertEquals(1, queue.nacks);
        assertEquals(1, queue.records.size());
        assertEquals("e-2", queue.records.get(0).eventId());
    }

    // ============ 测试替身 ============

    private static final class FakeQueue implements EventQueue {
        private final List<QueueRecord> records = new ArrayList<>();
        private final List<String> acked = new ArrayList<>();
        private final List<String> deadLettered = new ArrayList<>();
        private final Set<String> backingOff = new LinkedHashSet<>();
        private int nacks;

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public void publish(AgentEvent event) {
            records.add(new QueueRecord("r-" + event.eventId(), event, 0));
        }

        @Override
        public List<QueueRecord> poll(int max) {
            List<QueueRecord> page = new ArrayList<>();
            for (QueueRecord record : records) {
                if (page.size() >= max) {
                    break;
                }
                if (!backingOff.contains(record.id())) {
                    page.add(record);
                }
            }
            return page;
        }

        @Override
        public void ack(List<QueueRecord> batch) {
            for (QueueRecord record : batch) {
                acked.add(record.eventId());
                records.removeIf(r -> r.id().equals(record.id()));
            }
        }

        @Override
        public void nack(List<QueueRecord> batch, String reason) {
            nacks += batch.size();
            batch.forEach(record -> backingOff.add(record.id()));
        }

        @Override
        public void deadLetter(QueueRecord record, String reason) {
            deadLettered.add(record.eventId());
            records.removeIf(r -> r.id().equals(record.id()));
        }

        @Override
        public QueueStats stats() {
            return new QueueStats(records.size(), deadLettered.size(), 0L);
        }
    }

    private static final class RecordingWriter implements AgentEventFactWriter {
        private final List<Integer> batchSizes = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public int writeBatch(List<AgentEvent> events) {
            if (failWith != null) {
                throw failWith;
            }
            batchSizes.add(events.size());
            return events.size();
        }
    }

    private static final class RecordingMetrics implements EventQueueMetrics {
        private final List<QueueStats> backlogs = new ArrayList<>();
        private final List<String> deadLetters = new ArrayList<>();
        private int consumeFailures;

        @Override
        public void backlog(QueueStats stats, long lagMs) {
            backlogs.add(stats);
        }

        @Override
        public void deadLettered(QueueRecord record, String reason) {
            deadLetters.add(record.eventId());
        }

        @Override
        public void consumeFailed(int batchSize, Exception cause) {
            consumeFailures++;
        }
    }
}