package com.djzy.assistant.common.bus;

/**
 * 投递观测端口（§10.2）：消费滞后、死信一律告警。
 *
 * <p>这里只定义「要报什么」，具体落到 Prometheus / OTel 由适配层决定（§20.8）。
 */
public interface EventQueueMetrics {

    /** 每轮消费后报一次水位与落后时长（待处理 > 1 万条或落后 > 5s 即告警）。 */
    void backlog(QueueStats stats, long lagMs);

    /** 死信数 > 0 即告警：事件没能落进事实表，必须有人看。 */
    void deadLettered(QueueRecord record, String reason);

    void consumeFailed(int batchSize, Exception cause);

    static EventQueueMetrics noop() {
        return new EventQueueMetrics() {
            @Override
            public void backlog(QueueStats stats, long lagMs) {}

            @Override
            public void deadLettered(QueueRecord record, String reason) {}

            @Override
            public void consumeFailed(int batchSize, Exception cause) {}
        };
    }
}