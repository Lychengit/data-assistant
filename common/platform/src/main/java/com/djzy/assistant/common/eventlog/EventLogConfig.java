package com.djzy.assistant.common.eventlog;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 本地 append-only 日志配置（§19.6 / ADR-28）。
 *
 * <p>三样「会自己长大」的东西都在这里收口：**保留窗口**（确认投递过、又过了期的记录可以删）、
 * **单文件上限**（超过就把旧文件改名留档、新文件接着写）、**文件叫什么**。
 * 默认值按「一台应用服务器上放得下」来定：单文件 256 MB、保留 7 天。
 *
 * @param rootDir 日志根目录（docker-compose 用 named volume，K8s 用 PVC；**必须挂真实卷**）
 * @param instanceId 实例 id，活动文件名 {@code agent-{instanceId}.jsonl}
 * @param maxFileBytes 单文件滚动阈值（默认 256 MB）；文件到这个大小就滚动，见 {@link AppendOnlyEventLog#rollIfNeeded()}
 * @param fsyncBatchSize 按批 fsync 的条数（默认 50）
 * @param fsyncIntervalMs 按批 fsync 的时间间隔（默认 100ms，谁先到算谁）
 * @param retention 投递确认后的保留窗口（默认 7 天，可配）；比它更旧的已确认记录可以被裁掉
 */
public record EventLogConfig(
        Path rootDir, String instanceId, long maxFileBytes, int fsyncBatchSize, long fsyncIntervalMs, Duration retention) {

    public static final int DEFAULT_FSYNC_BATCH_SIZE = 50;
    public static final long DEFAULT_FSYNC_INTERVAL_MS = 100L;
    public static final long DEFAULT_MAX_FILE_BYTES = 256L * 1024 * 1024;

    /** 默认保留窗口：7 天。取这个数的理由——够覆盖「出问题到有人来看」的排查窗口，又不至于把磁盘占满。 */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    /** 留档文件名里的时间戳（本地时间，人看着顺眼；排序与找「最旧的」都用它）。 */
    private static final DateTimeFormatter ROTATED_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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
        if (retention == null || retention.isZero() || retention.isNegative()) {
            retention = DEFAULT_RETENTION;
        }
    }

    /** 正在写的那个文件（读位点只在这个文件里有意义）。 */
    public Path logFile() {
        return rootDir.resolve("agent-" + instanceId + ".jsonl");
    }

    public Path cursorFile() {
        return rootDir.resolve("agent-" + instanceId + ".ack");
    }

    /** 滚动后的留档文件名：{@code agent-{instanceId}-{时间戳}.jsonl}。 */
    public Path rotatedFile(long atMs) {
        return rootDir.resolve(rotatedName(atMs));
    }

    /** 留档文件的名字长这样；清理时按「前缀 + 后缀」找它们。 */
    public String rotatedNamePrefix() {
        return "agent-" + instanceId + "-";
    }

    private String rotatedName(long atMs) {
        return rotatedNamePrefix()
                + ROTATED_STAMP.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))
                + ".jsonl";
    }

    /**
     * 从留档文件名里读回「它是什么时候滚出去的」（epoch 毫秒）；读不出来返回 {@code -1}。
     *
     * <p>用名字里的时间戳而不是文件修改时间：文件被拷来拷去时 mtime 会变，名字不会。
     * 读不出来（例如有人手工改了名字）时由调用方退回看 mtime——总之不要因为一个不认识的文件就报错。
     */
    public static long rotatedAtMs(String fileName) {
        if (fileName == null || !fileName.endsWith(".jsonl")) {
            return -1L;
        }
        int dash = fileName.lastIndexOf('-');
        if (dash < 0 || dash + 1 >= fileName.length() - ".jsonl".length()) {
            return -1L;
        }
        String stamp = fileName.substring(dash + 1, fileName.length() - ".jsonl".length());
        try {
            return java.time.LocalDateTime.parse(stamp, ROTATED_STAMP)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
