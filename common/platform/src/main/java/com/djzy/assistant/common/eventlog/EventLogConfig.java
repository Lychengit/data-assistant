package com.djzy.assistant.common.eventlog;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 本地 append-only 日志配置（§19.6 / ADR-28）。
 *
 * @param rootDir 日志根目录（docker-compose 用 named volume，K8s 用 PVC；**必须挂真实卷**）
 * @param instanceId 实例 id，文件名 {@code agent-{instanceId}.jsonl}
 * @param maxFileBytes 单文件滚动阈值
 * @param fsyncBatchSize 按批 fsync 的条数（默认 50）
 * @param fsyncIntervalMs 按批 fsync 的时间间隔（默认 100ms，谁先到算谁）
 * @param retention 投递确认后的保留窗口（默认 24h，可配）
 */
public record EventLogConfig(
        Path rootDir, String instanceId, long maxFileBytes, int fsyncBatchSize, long fsyncIntervalMs, Duration retention) {

    public static final int DEFAULT_FSYNC_BATCH_SIZE = 50;
    public static final long DEFAULT_FSYNC_INTERVAL_MS = 100L;
    public static final long DEFAULT_MAX_FILE_BYTES = 256L * 1024 * 1024;

    public EventLogConfig {
        Objects.requireNonNull(rootDir, "rootDir");
        Objects.requireNonNull(instanceId, "instanceId");
        if (maxFileBytes <= 0) {
            maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        }
        if (fsyncBatchSize <= 0) {
            fsyncBatchSize = DEFAULT_FSYNC_BATCH_SIZE;
        }
        if (fsyncIntervalMs <= 0) {
            fsyncIntervalMs = DEFAULT_FSYNC_INTERVAL_MS;
        }
        if (retention == null) {
            retention = Duration.ofHours(24);
        }
    }

    public Path logFile() {
        return rootDir.resolve("agent-" + instanceId + ".jsonl");
    }

    public Path cursorFile() {
        return rootDir.resolve("agent-" + instanceId + ".ack");
    }
}
