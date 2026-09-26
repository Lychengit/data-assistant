package com.djzy.assistant.common.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 日志配置（H-13）：默认值怎么兜、留档文件名怎么带出时间戳。
 *
 * <p>为什么值得单测：这两个都是「配错了不会报错、只会慢慢出问题」的地方——
 * 单文件上限配成 0 会让文件永远不滚动（磁盘慢慢占满），保留窗口配成 0 会把刚写的东西当过期删掉。
 * 所以负值 / 0 一律收敛到默认值，而不是照字面执行。
 */
class EventLogConfigTest {

    @Test
    void 上限或窗口配成零和负数就退回默认值(@TempDir Path dir) {
        EventLogConfig config = new EventLogConfig(dir, "i-1", 0, 0, 0, null);

        assertEquals(EventLogConfig.DEFAULT_MAX_FILE_BYTES, config.maxFileBytes());
        assertEquals(EventLogConfig.DEFAULT_FSYNC_BATCH_SIZE, config.fsyncBatchSize());
        assertEquals(EventLogConfig.DEFAULT_FSYNC_INTERVAL_MS, config.fsyncIntervalMs());
        assertEquals(EventLogConfig.DEFAULT_RETENTION, config.retention());
        assertEquals(Duration.ofDays(7), config.retention(), "默认保留窗口就是 7 天，便于「出问题后有人来看」");
    }

    @Test
    void 保留窗口为零或负数同样退回默认值(@TempDir Path dir) {
        assertEquals(
                EventLogConfig.DEFAULT_RETENTION, new EventLogConfig(dir, "i-1", 1024, 1, 1, Duration.ZERO).retention());
        assertEquals(
                EventLogConfig.DEFAULT_RETENTION,
                new EventLogConfig(dir, "i-1", 1024, 1, 1, Duration.ofDays(-1)).retention());
    }

    @Test
    void 活动文件与位点文件的名字里都带实例号(@TempDir Path dir) {
        EventLogConfig config = new EventLogConfig(dir, "i-7", 1024, 1, 1, Duration.ofDays(7));

        assertEquals("agent-i-7.jsonl", config.logFile().getFileName().toString());
        assertEquals("agent-i-7.ack", config.cursorFile().getFileName().toString());
    }

    @Test
    void 留档文件名能读出滚动的时刻(@TempDir Path dir) {
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1024, 1, 1, Duration.ofDays(7));
        long at = System.currentTimeMillis();

        String name = config.rotatedFile(at).getFileName().toString();
        long parsed = EventLogConfig.rotatedAtMs(name);

        assertTrue(name.startsWith("agent-i-1-"), "留档名要能被「前缀 + 后缀」认出来：" + name);
        // 文件名里的时间戳精确到秒，所以只要求落在同一秒内
        assertTrue(Math.abs(parsed - at) < 1000L, "读出 " + parsed + "，写入 " + at);
    }

    @Test
    void 名字里读不出时刻就返回减一交给调用方兜底() {
        // 认不出来的名字（手工改过 / 别的文件）不能让维护任务报错，只回报「不知道」
        assertEquals(-1L, EventLogConfig.rotatedAtMs("agent-i-1.jsonl"));
        assertEquals(-1L, EventLogConfig.rotatedAtMs("agent-i-1-notatimestamp.jsonl"));
        assertEquals(-1L, EventLogConfig.rotatedAtMs("random.txt"));
        assertEquals(-1L, EventLogConfig.rotatedAtMs(null));
    }
}
