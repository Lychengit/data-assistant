package com.djzy.assistant.common.bus;

import java.util.List;

/**
 * 落库通道（§19.6 / §8.4 / ADR-10）：队列**只是**把事件搬到 PG 事实表的通道，
 * **不是**唯一副本——唯一副本是本地 append-only 日志（ADR-28）。
 *
 * <p>因此这里的语义是 **at-least-once**：重复投递必须由下游按 {@code event_id} 幂等吸收
 * （事实表唯一键 + {@code ON CONFLICT DO NOTHING}）。
 *
 * <p>骨架期实现 {@code pg-outbox} 与 {@code redis-stream}，换 RocketMQ 只换实现：
 * {@link EventPersistConsumer} 与业务代码不动。
 */
public interface EventQueue extends EventPublisher {

    /** {@code pg-outbox} / {@code redis-stream} / 预留 {@code rocketmq}。 */
    String type();

    /** 拉一批未确认事件（至多 {@code max} 条）。空队列返回空表，**不阻塞等待**。 */
    List<QueueRecord> poll(int max);

    /** 确认已落库：从队列摘除（PG 标记 {@code delivered_at} / Redis {@code XACK}）。 */
    void ack(List<QueueRecord> records);

    /**
     * 本次投递失败：不确认，交给队列退避重试
     * （PG 用 {@code attempts + next_attempt_at} 退避；Redis 由 {@code XPENDING} 投递计数承载）。
     */
    void nack(List<QueueRecord> records, String reason);

    /** 超过重试上限：写死信表并出队（不无限重投，§19.6）。 */
    void deadLetter(QueueRecord record, String reason);

    QueueStats stats();
}