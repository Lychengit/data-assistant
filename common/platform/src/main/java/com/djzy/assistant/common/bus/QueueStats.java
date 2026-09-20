package com.djzy.assistant.common.bus;

/**
 * 队列水位（§10.2）：待处理 > 1 万条、或落后 > 5s、或死信数 > 0，一律告警。
 *
 * @param pending              未确认（未落库）条数
 * @param deadLettered         死信条数
 * @param oldestPendingEpochMs 最早一条未确认事件的入队时刻；无积压时为 0
 */
public record QueueStats(long pending, long deadLettered, long oldestPendingEpochMs) {

    public static QueueStats empty() {
        return new QueueStats(0L, 0L, 0L);
    }

    /** 落后时长（毫秒）：无积压记 0。 */
    public long lagMs(long nowEpochMs) {
        return oldestPendingEpochMs <= 0L ? 0L : Math.max(0L, nowEpochMs - oldestPendingEpochMs);
    }
}