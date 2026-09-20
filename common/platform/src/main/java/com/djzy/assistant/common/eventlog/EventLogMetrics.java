package com.djzy.assistant.common.eventlog;

/**
 * 日志观测端口（§10.2 / ADR-28 ⑤）：未投递积压（条数 + 最早未确认时间）与磁盘水位一律告警。
 */
public interface EventLogMetrics {

    void unconfirmedBacklog(long count, long oldestUnconfirmedEpochMs);

    void writeFailed(Exception cause);

    void diskUsage(long usedBytes, long totalBytes);

    static EventLogMetrics noop() {
        return new EventLogMetrics() {
            @Override
            public void unconfirmedBacklog(long count, long oldestUnconfirmedEpochMs) {}

            @Override
            public void writeFailed(Exception cause) {}

            @Override
            public void diskUsage(long usedBytes, long totalBytes) {}
        };
    }
}
