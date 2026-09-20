package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 历史会话分组（§19.4）：一轮的边界由「用户提问」划，不是由 turnId 划。 */
class SessionHistoryTest {

    @Test
    void 用户提问起一轮_后续事件并入同一轮() {
        List<SessionHistory.TurnSlice> turns = SessionHistory.group(List.of(
                record(0, SseEventType.SESSION, Map.of("sessionId", "s1")),
                record(1, SseEventType.USER, Map.of("text", "心内科门诊量", "turnId", "t1")),
                record(2, SseEventType.TOKEN, Map.of("delta", "答")),
                record(3, SseEventType.DONE, Map.of("turnId", "t1"))));

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).turnId()).isEqualTo("t1");
        // session 是连接元数据，不是对话内容：不进任何一轮
        assertThat(turns.get(0).events())
                .extracting(SessionHistory.StreamEventView::name)
                .containsExactly("user", "token", "done");
    }

    @Test
    void 两轮各自成组() {
        List<SessionHistory.TurnSlice> turns = SessionHistory.group(List.of(
                record(0, SseEventType.USER, Map.of("text", "第一问", "turnId", "t1")),
                record(1, SseEventType.TOKEN, Map.of("delta", "一")),
                record(2, SseEventType.DONE, Map.of("turnId", "t1")),
                record(3, SseEventType.USER, Map.of("text", "第二问", "turnId", "t2")),
                record(4, SseEventType.DONE, Map.of("turnId", "t2"))));

        assertThat(turns).extracting(SessionHistory.TurnSlice::turnId).containsExactly("t1", "t2");
        assertThat(turns.get(1).events()).hasSize(2);
    }

    @Test
    void HITL确认续跑并入同一轮() {
        // 确认会换一个新 turnId，但用户眼里仍是同一条回答：划成两轮会把答案劈成两半
        List<SessionHistory.TurnSlice> turns = SessionHistory.group(List.of(
                record(0, SseEventType.USER, Map.of("text", "登记这个查询", "turnId", "t1")),
                record(1, SseEventType.CONFIRM, Map.of("confirmId", "c1", "action", "register")),
                record(2, SseEventType.DONE, Map.of("turnId", "t1")),
                record(3, SseEventType.CONFIRM, Map.of("confirmId", "c1", "approved", true, "phase", "result")),
                record(4, SseEventType.TOKEN, Map.of("delta", "已执行")),
                record(5, SseEventType.DONE, Map.of("turnId", "t2"))));

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).events()).hasSize(6);
    }

    @Test
    void 老会话没有用户提问_从第一条非会话记录起一轮() {
        // USER_MESSAGE 上线前写下的会话：正文从来不在日志里（TURN_START 也不下发）。
        // 答案是有的——丢掉整段更糟，所以照样回放，问题一栏留给前端说明。
        List<SessionHistory.TurnSlice> turns = SessionHistory.group(List.of(
                record(0, SseEventType.SESSION, Map.of("sessionId", "s1")),
                record(1, SseEventType.TOKEN, Map.of("delta", "答")),
                record(2, SseEventType.DONE, Map.of("turnId", "t1"))));

        assertThat(turns).hasSize(1);
        // 没有可用的 turnId 时回退成 seq：它要当前端列表的 key，必须唯一
        assertThat(turns.get(0).turnId()).isEqualTo("turn-1");
        assertThat(turns.get(0).events())
                .extracting(SessionHistory.StreamEventView::name)
                .containsExactly("token", "done");
    }

    /**
     * 归档是会话级状态，不属于任何一轮：混进分组会在历史里长出「一轮只有一条 archived」的空对话，
     * 而空会话被归档时会凭空多出一轮。
     */
    @Test
    void 归档事件不进轮次分组() {
        List<SessionHistory.TurnSlice> turns = SessionHistory.group(List.of(
                record(0, SseEventType.SESSION, Map.of("sessionId", "s1")),
                record(1, SseEventType.ARCHIVED, Map.of("archived", true)),
                record(2, SseEventType.USER, Map.of("text", "第一问", "turnId", "t1")),
                record(3, SseEventType.DONE, Map.of("turnId", "t1")),
                record(4, SseEventType.ARCHIVED, Map.of("archived", false))));

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).events())
                .extracting(SessionHistory.StreamEventView::name)
                .containsExactly("user", "done");
    }

    /** 只被归档、从没问过的会话：回放出来是空的，而不是一轮空对话。 */
    @Test
    void 只有归档记录的会话回放出空列表() {
        assertThat(SessionHistory.group(List.of(
                        record(0, SseEventType.SESSION, Map.of("sessionId", "s1")),
                        record(1, SseEventType.ARCHIVED, Map.of("archived", true)))))
                .isEmpty();
    }

    @Test
    void 空日志回放出空列表() {
        assertThat(SessionHistory.group(List.of())).isEmpty();
        assertThat(SessionHistory.group(List.of(record(0, SseEventType.SESSION, Map.of("sessionId", "s1")))))
                .isEmpty();
    }

    private static JournalRecord record(long seq, SseEventType type, Map<String, Object> payload) {
        return new JournalRecord(seq, SseEvent.of(type, payload), System.currentTimeMillis());
    }
}