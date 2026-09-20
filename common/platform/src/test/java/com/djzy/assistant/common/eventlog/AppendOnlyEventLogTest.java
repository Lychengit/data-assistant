package com.djzy.assistant.common.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** §19.6 / ADR-28：日志先行、事件不消失、重启可追赶、卷不可用拒绝启动。 */
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

    private static EventLogConfig config(Path dir) {
        return new EventLogConfig(dir, "i-1", 1024 * 1024, 50, 100, Duration.ofHours(24));
    }

    private static AgentEvent event(String eventId, String turnId, long seq) {
        return AgentEvent.builder(AgentEventType.TOOL_RESULT)
                .eventId(eventId)
                .session("s-1")
                .turn(turnId)
                .trace("trace-1")
                .seq(seq)
                .putAll(Map.of("toolName", "iface_profit", "status", "ok"))
                .build();
    }
}
