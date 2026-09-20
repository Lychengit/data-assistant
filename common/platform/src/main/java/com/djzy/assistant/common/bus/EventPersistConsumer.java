package com.djzy.assistant.common.bus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * event-persist 消费者（§19.6）：按消费组拉取 → **攒批** → 一批一个事务写入 PG 事实表 → 确认。
 *
 * <p>攒批节奏与本地日志按批 fsync 同一套口径：攒够 {@value #DEFAULT_BATCH_SIZE} 条、
 * 或距本次攒批开始 {@value #DEFAULT_FLUSH_INTERVAL_MS}ms、或队列已空，谁先到算谁；
 * 单批上限 {@value #DEFAULT_MAX_BATCH} 条。
 *
 * <p>失败处理：**不确认**，交给队列退避重试；失败次数达到 {@value #DEFAULT_MAX_ATTEMPTS} 次即进死信表，
 * 不无限重投。重复投递不会写重：事实表的 {@code event_id} 唯一键 + {@code ON CONFLICT DO NOTHING}。
 *
 * <p>用户等待路径上没有这个消费者：它在后台把日志里已经落定的事件搬进事实表，慢一点只影响回放。
 */
public final class EventPersistConsumer {

    public static final int DEFAULT_BATCH_SIZE = 50;
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 100L;
    public static final int DEFAULT_MAX_BATCH = 500;
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(EventPersistConsumer.class);

    private final EventQueue queue;
    private final AgentEventFactWriter writer;
    private final EventQueueMetrics metrics;
    private final int batchSize;
    private final long flushIntervalMs;
    private final int maxBatch;
    private final int maxAttempts;

    public EventPersistConsumer(EventQueue queue, AgentEventFactWriter writer, EventQueueMetrics metrics) {
        this(
                queue,
                writer,
                metrics,
                DEFAULT_BATCH_SIZE,
                DEFAULT_FLUSH_INTERVAL_MS,
                DEFAULT_MAX_BATCH,
                DEFAULT_MAX_ATTEMPTS);
    }

    public EventPersistConsumer(
            EventQueue queue,
            AgentEventFactWriter writer,
            EventQueueMetrics metrics,
            int batchSize,
            long flushIntervalMs,
            int maxBatch,
            int maxAttempts) {
        this.queue = queue;
        this.writer = writer;
        this.metrics = metrics == null ? EventQueueMetrics.noop() : metrics;
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(0L, flushIntervalMs);
        this.maxBatch = Math.max(this.batchSize, maxBatch);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * 跑一轮：按攒批节奏拉一批并落库。
     *
     * @return 本批**实际新增**的事实行数；队列空时为 0
     */
    public int drainOnce() {
        List<QueueRecord> batch = collect();
        QueueStats stats = queue.stats();
        metrics.backlog(stats, stats.lagMs(System.currentTimeMillis()));
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            int rows = writer.writeBatch(batch.stream().map(QueueRecord::event).toList());
            queue.ack(batch);
            return rows;
        } catch (RuntimeException e) {
            log.error("事件落库失败（不确认，交给队列重试）：batchSize={}", batch.size(), e);
            metrics.consumeFailed(batch.size(), e);
            retainOrDeadLetter(batch, e);
            return 0;
        }
    }

    private List<QueueRecord> collect() {
        List<QueueRecord> batch = new ArrayList<>();
        // poll 是**无状态**的（PG Outbox 会重复返回同一行，直到它被确认），所以必须自己去重：
        // 否则一批会塞满同一条事件的副本，落库时看着「攒够了 50 条」，实际只有 1 条。
        Set<String> seen = new HashSet<>();
        long deadline = System.currentTimeMillis() + flushIntervalMs;
        while (batch.size() < maxBatch) {
            List<QueueRecord> page = queue.poll(maxBatch - batch.size());
            int added = 0;
            for (QueueRecord record : page) {
                if (seen.add(record.id())) {
                    batch.add(record);
                    added++;
                }
            }
            // 没有新记录（队列空了，或剩下的都在退避中）：这一批就到这
            if (added == 0) {
                break;
            }
            if (batch.size() >= batchSize || System.currentTimeMillis() >= deadline) {
                break;
            }
        }
        return batch;
    }

    /** 失败的这批：到上限的进死信，其余留着退避重试。 */
    private void retainOrDeadLetter(List<QueueRecord> batch, RuntimeException cause) {
        String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        List<QueueRecord> retry = new ArrayList<>();
        for (QueueRecord record : batch) {
            if (record.attempts() + 1 >= maxAttempts) {
                queue.deadLetter(record, reason);
                metrics.deadLettered(record, reason);
            } else {
                retry.add(record);
            }
        }
        if (!retry.isEmpty()) {
            queue.nack(retry, reason);
        }
    }
}
