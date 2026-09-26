package com.djzy.assistant.common.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §19.6 / ADR-28：日志先行、事件不消失、重启可追赶、卷不可用拒绝启动。
 *
 * <p>后面几组是 H-13 加的：**本地日志怎么才能不长到把磁盘撑爆**——裁剪（只删已确认且超期的前缀）与
 * 滚动（活动文件超上限就换一个，旧文件留档），以及留档的到期清理。共同的红线只有一条：
 * **还没确认投递的记录一条都不能少**，删了就是丢事件。
 */
class AppendOnlyEventLogTest {

    @Test
    void appendsAndScansInOrder(@TempDir Path dir) {
        EventLogConfig config = config(dir);
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            log.append(event("e1", "t1", 1));
            log.append(event("e2", "t1", 2));
            log.flush();

            List<AgentEvent> events = log.scanFrom(0);
            assertEquals(List.of("e1", "e2"), events.stream().map(AgentEvent::eventId).toList());
            assertEquals(2, events.get(1).seq());
        }
    }

    @Test
    void unackedEventsAreRedeliveredAfterRestart(@TempDir Path dir) {
        EventLogConfig config = config(dir);
        long end;
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            log.append(event("e1", "t1", 1));
            AppendResult result = log.append(event("e2", "t1", 2));
            end = result.endOffset();
        }
        // 模拟进程重启：新实例读同一份日志
        try (AppendOnlyEventLog restarted =
                new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            assertEquals(2, restarted.pendingRedelivery().size(), "未确认投递的事件必须全部重新入队");
            restarted.ack(end);
            assertTrue(restarted.pendingRedelivery().isEmpty(), "确认投递后不应重投");
        }
        assertNotNull(end);
    }

    @Test
    void unacknowledgedEventsSurviveCrash(@TempDir Path dir) throws Exception {
        EventLogConfig config = config(dir);
        AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()));
        log.append(event("e1", "t1", 1));
        log.flush();
        // 不调用 close()，模拟 kill -9
        assertTrue(Files.size(config.logFile()) > 0);
        assertEquals(1, log.scanFrom(0).size());
    }

    @Test
    void refusesToStartWhenVolumeIsNotWritable(@TempDir Path dir) throws Exception {
        Path fileInsteadOfDir = dir.resolve("not-a-directory");
        Files.writeString(fileInsteadOfDir, "x");
        EventLogConfig config = new EventLogConfig(
                fileInsteadOfDir, "i-1", 1024, 1, 1, Duration.ofHours(24));

        assertThrows(
                EventLogUnavailableException.class,
                () -> new AppendOnlyEventLog(config, new FileEventLogCursorStore(dir.resolve("ack"))));
    }

    @Test
    void 未确认投递的记录一条都不裁(@TempDir Path dir) {
        // 保留窗口设成 1 毫秒、事件时间又都是很久以前：只要「超期」就该裁，
        // 于是能拦住裁剪的只可能是「还没确认投递」这一条
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1024 * 1024, 50, 100, Duration.ofMillis(1));
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            log.append(event("e1", "t1", 1, old()));
            log.append(event("e2", "t1", 2, old()));
            log.flush();

            assertFalse(log.trimAcked(), "一条都没确认投递，就没有东西可裁");
            assertEquals(
                    List.of("e1", "e2"),
                    log.scanFrom(0).stream().map(AgentEvent::eventId).toList(),
                    "未确认的记录必须原样留着，它们还要重投");
        }
    }

    @Test
    void 已确认且超期的前缀会被裁掉_剩下的内容与位点都对(@TempDir Path dir) throws Exception {
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1024 * 1024, 50, 100, Duration.ofHours(24));
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            log.append(event("e1", "t1", 1, old()));
            long end = log.append(event("e2", "t1", 2, now())).endOffset();
            log.flush();
            log.ack(end);

            assertTrue(log.trimAcked(), "e1 既已确认投递又超期，应该被裁掉");

            // 剩下的只有 e2，一行不多一行不少
            assertEquals(List.of("e2"), log.scanFrom(0).stream().map(AgentEvent::eventId).toList());
            // 位点要跟着前移：裁剪后「全都已确认」就该是文件末尾，否则重启会把 e2 又投一遍
            assertEquals(Files.size(config.logFile()), log.ackedOffset());
            assertTrue(log.pendingRedelivery().isEmpty(), "裁完不该冒出新的待重投");
            // 位点还必须是落盘的：换一个实例（重启）读同一份文件，读到的也该是裁剪后的值
            assertEquals(log.ackedOffset(), new FileEventLogCursorStore(config.cursorFile()).load());
            assertFalse(Files.readString(config.logFile()).contains("\"e1\""), "被裁掉的那行不该还留在文件里");
        }
    }

    @Test
    void 活动文件超上限就滚动_旧文件留档_未确认的尾巴搬进新文件(@TempDir Path dir) throws Exception {
        // 上限压到 1 字节：写一条就超，滚动必定被触发，不用凑数据
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1, 50, 100, Duration.ofHours(24));
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            long firstEnd = log.append(event("e1", "t1", 1)).endOffset();
            log.append(event("e2", "t1", 2));
            log.flush();
            log.ack(firstEnd); // 只确认了 e1：e2 还在等投递

            assertTrue(log.rollIfNeeded(), "文件已经超过上限，应该滚动");

            assertEquals(0L, log.ackedOffset(), "新文件里只有还没送出去的部分，位点自然归零");
            assertEquals(
                    List.of("e2"),
                    log.pendingRedelivery().stream().map(AgentEvent::eventId).toList(),
                    "未确认的 e2 必须跟到新文件里，否则重启就漏投了");

            List<Path> rotated = rotatedFiles(dir, config);
            assertEquals(1, rotated.size(), "旧文件应该留档一份");
            // 留档的是整份旧文件（e1 也在里面）：滚动只负责「换个文件接着写」，不做删除
            assertTrue(Files.readString(rotated.get(0)).contains("\"e1\""));
            assertTrue(
                    EventLogConfig.rotatedAtMs(rotated.get(0).getFileName().toString()) > 0,
                    "留档文件名里要带得出时间戳，清理时按它判年龄");

            String active = Files.readString(config.logFile());
            assertTrue(active.contains("\"e2\""));
            assertFalse(active.contains("\"e1\""), "已确认的旧内容不该跟着搬进新文件");
        }
    }

    @Test
    void 一条都没确认投递时即使超上限也不滚动(@TempDir Path dir) {
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1, 50, 100, Duration.ofHours(24));
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            log.append(event("e1", "t1", 1));
            log.flush();

            // 这时文件里全是「还没送出去」的记录：滚过去只是把它整个复制一遍，
            // 既没腾出空间，又会每分钟再造一份同样大的留档——所以宁可先不滚
            assertFalse(log.rollIfNeeded());
            assertTrue(rotatedFiles(dir, config).isEmpty(), "不该造出留档文件");
        }
    }

    @Test
    void 清理留档_到点的删掉_没到点的留着(@TempDir Path dir) throws Exception {
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1024 * 1024, 50, 100, Duration.ofDays(7));
        long now = System.currentTimeMillis();
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            Path expired = Files.createFile(config.rotatedFile(now - Duration.ofDays(8).toMillis()));
            Path fresh = Files.createFile(config.rotatedFile(now));

            assertEquals(1, log.pruneRotated(), "只该删掉过期的那一个");

            assertFalse(Files.exists(expired), "过了保留窗口的留档该删");
            assertTrue(Files.exists(fresh), "还在保留窗口里的留档不能删");
            assertTrue(Files.exists(config.logFile()), "正在写的活动文件更不该被清理顺手删掉");
        }
    }

    @Test
    void 清理留档时顺带收走崩溃留下的临时文件(@TempDir Path dir) throws Exception {
        EventLogConfig config = new EventLogConfig(dir, "i-1", 1024 * 1024, 50, 100, Duration.ofDays(7));
        try (AppendOnlyEventLog log = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()))) {
            // 正常路径上这个文件写完就被改名替换掉了，留在这里的只能是「抄到一半崩了」
            Path abandoned = dir.resolve(config.logFile().getFileName() + ".tmp");
            Files.writeString(abandoned, "抄到一半就断电了");

            // 名字里没有时间戳，年龄只能看修改时间：刚写的不能删（万一它正在被写）
            assertEquals(0, log.pruneRotated());
            assertTrue(Files.exists(abandoned));

            // 把它伪造成很久以前留下的，就该被收走
            Files.setLastModifiedTime(abandoned, FileTime.fromMillis(old()));
            assertEquals(1, log.pruneRotated());
            assertFalse(Files.exists(abandoned));
        }
    }

    /** 目录里属于这个实例的留档文件（{@code agent-{instanceId}-{时间戳}.jsonl}）。 */
    private static List<Path> rotatedFiles(Path dir, EventLogConfig config) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(config.rotatedNamePrefix()))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 「早就超期了」的时刻：比任何常见保留窗口都早。 */
    private static long old() {
        return System.currentTimeMillis() - Duration.ofDays(30).toMillis();
    }

    private static EventLogConfig config(Path dir) {
        return new EventLogConfig(dir, "i-1", 1024 * 1024, 50, 100, Duration.ofHours(24));
    }

    private static AgentEvent event(String eventId, String turnId, long seq) {
        return event(eventId, turnId, seq, System.currentTimeMillis());
    }

    private static AgentEvent event(String eventId, String turnId, long seq, long timestampEpochMs) {
        return AgentEvent.builder(AgentEventType.TOOL_RESULT)
                .eventId(eventId)
                .session("s-1")
                .turn(turnId)
                .trace("trace-1")
                .seq(seq)
                .timestamp(timestampEpochMs)
                .putAll(Map.of("toolName", "iface_profit", "status", "ok"))
                .build();
    }
}
