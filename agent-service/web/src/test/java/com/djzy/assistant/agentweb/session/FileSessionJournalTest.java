package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSessionJournalTest {

    @TempDir
    Path dir;

    @Test
    void 重启后仍能回放且不串号() {
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i1", 64)) {
            journal.append("alice", "s1", record(0));
            journal.append("alice", "s1", record(1));
            journal.append("bob", "s1", record(0));
        }

        // 新实例 = 进程重启：内存索引为空，必须从文件重建
        try (FileSessionJournal reopened = new FileSessionJournal(dir, "i1", 64)) {
            assertThat(reopened.readAfter("alice", "s1", -1)).hasSize(2);
            assertThat(reopened.readAfter("alice", "s1", 0)).extracting(JournalRecord::seq).containsExactly(1L);
            assertThat(reopened.lastSeq("alice", "s1")).isEqualTo(1L);
            // 归属不符：同一个 sessionId 换个人读，什么都不给
            assertThat(reopened.readAfter("mallory", "s1", -1)).isEmpty();
        }
    }

    @Test
    void 未落盘的记录也立即可读() {
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i2", 64)) {
            journal.append("alice", "s2", record(7));
            assertThat(journal.readAfter("alice", "s2", 6)).extracting(JournalRecord::seq).containsExactly(7L);
        }
    }

    /** §19.4：历史会话列表——最近聊过的在最上面，而且**只含自己的**（§11.3）。 */
    @Test
    void 会话列表按最后活动倒序_且只含自己的() {
        long now = System.currentTimeMillis();
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i3", 64)) {
            journal.append("alice", "old", record(0, now - 10_000));
            journal.append("alice", "fresh", record(0, now));
            journal.append("alice", "fresh", record(1, now + 1_000));
            journal.append("bob", "bob-only", record(0, now + 5_000));
        }

        // 新实例 = 进程重启：列表也必须从文件重建，否则「历史会话」一重启就空了
        try (FileSessionJournal reopened = new FileSessionJournal(dir, "i3", 64)) {
            assertThat(reopened.listSessions("alice"))
                    .extracting(SessionSummary::sessionId)
                    .containsExactly("fresh", "old");
            assertThat(reopened.listSessions("bob")).extracting(SessionSummary::sessionId).containsExactly("bob-only");
            assertThat(reopened.listSessions("mallory")).isEmpty();
        }
    }

    /** 标题就是首条用户提问：列表里只有一行字，标题得能认出是哪次对话。 */
    @Test
    void 标题取首条用户提问_没问过就是未命名会话() {
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i4", 64)) {
            // blank 先写：列表按最后活动倒序，两个会话的时刻必须能分出先后
            journal.append("alice", "blank", record(0));
            journal.append("alice", "ask", user(0, "  上个月  心内科门诊量  "));
            journal.append("alice", "ask", user(1, "第二个问题不该改标题"));

            assertThat(journal.listSessions("alice"))
                    .extracting(SessionSummary::sessionId, SessionSummary::title, SessionSummary::questions)
                    .containsExactly(tuple("ask", "上个月 心内科门诊量", 2), tuple("blank", "未命名会话", 0));
        }
    }

    /** §19.4 归档：位从回放位派生，所以重启后仍在，而且「最后一次说了算」。 */
    @Test
    void 归档位随日志持久化_且最后一次说了算() {
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i5", 64)) {
            journal.append("alice", "s", record(0));
            journal.append("alice", "s", archived(1, true));
        }

        // 新实例 = 进程重启：归档不是只活在内存里的标记
        try (FileSessionJournal reopened = new FileSessionJournal(dir, "i5", 64)) {
            assertThat(reopened.listSessions("alice"))
                    .extracting(SessionSummary::sessionId, SessionSummary::archived)
                    .containsExactly(tuple("s", true));
            // 归档不是单向闸门：取消归档后立刻回到未归档
            reopened.append("alice", "s", archived(2, false));
            assertThat(reopened.listSessions("alice"))
                    .extracting(SessionSummary::archived)
                    .containsExactly(false);
        }

        // 再重启一次：取消归档这件事同样在日志里，不会「重启又变回已归档」
        try (FileSessionJournal reopened = new FileSessionJournal(dir, "i5", 64)) {
            assertThat(reopened.listSessions("alice"))
                    .extracting(SessionSummary::archived)
                    .containsExactly(false);
        }
    }

    /** 列表要能显示「什么时候收起来的」：归档时刻就是写下那条记录的时刻。 */
    @Test
    void 归档时刻是写下那条记录的时刻() {
        long at = System.currentTimeMillis();
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i6", 64)) {
            journal.append("alice", "s", record(0, at));
            journal.append("alice", "s", new JournalRecord(1, SseEvent.of(SseEventType.ARCHIVED, Map.of("archived", true)), at + 500));

            SessionSummary summary = journal.listSessions("alice").get(0);
            assertThat(summary.archived()).isTrue();
            assertThat(summary.archivedAtMs()).isEqualTo(at + 500);
        }
    }

    /** 没归档过的会话，两个字段都必须是干净的初值：前端靠它决定这一行放哪个分区。 */
    @Test
    void 没归档过的会话_归档字段是初值() {
        try (FileSessionJournal journal = new FileSessionJournal(dir, "i7", 64)) {
            journal.append("alice", "s", record(0));
            SessionSummary summary = journal.listSessions("alice").get(0);
            assertThat(summary.archived()).isFalse();
            assertThat(summary.archivedAtMs()).isZero();
        }
    }

    private static JournalRecord archived(long seq, boolean archived) {
        return new JournalRecord(
                seq, SseEvent.of(SseEventType.ARCHIVED, Map.of("archived", archived)), System.currentTimeMillis());
    }

    private static JournalRecord record(long seq) {
        return record(seq, System.currentTimeMillis());
    }

    private static JournalRecord record(long seq, long atMs) {
        return new JournalRecord(seq, SseEvent.of(SseEventType.TOKEN, Map.of("delta", "x" + seq)), atMs);
    }

    private static JournalRecord user(long seq, String text) {
        return new JournalRecord(seq, SseEvent.of(SseEventType.USER, Map.of("text", text)), System.currentTimeMillis());
    }
}
