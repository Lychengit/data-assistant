package com.djzy.assistant.agentweb.session;

import java.util.List;

/**
 * 会话事件回放位（§19.4 断线续传）。
 *
 * <p>它记录的是**已经下发给前端的 SSE 事件序列**，因此从它回放就能让刷新后的页面
 * 无缝接上原来那一轮回答；它与 §19.6 的落库日志是同一份「发生过的顺序」，不是第二套事实源：
 * 实现必须 append-only，且**先落记录再推送**（否则断线时前端看到的会比重连后回放的多）。
 */
public interface SessionJournal {

    /** @param userId 会话归属人；回放时由它做归属校验，**不能只凭 sessionId 就能读到别人的回答** */
    void append(String userId, String sessionId, JournalRecord record);

    /** @return 该用户该会话中 {@code seq > afterSeq} 的记录，按 seq 升序；归属不符返回空 */
    List<JournalRecord> readAfter(String userId, String sessionId, long afterSeq);

    /** 已有记录中的最大 seq；没有记录时返回 -1。 */
    long lastSeq(String userId, String sessionId);

    /**
     * 该用户的会话列表（按最后活动倒序）。
     *
     * <p>**只返回 {@code userId} 自己的会话**：列表是最容易泄露「别人有哪些会话」的地方（§11.3）。
     */
    List<SessionSummary> listSessions(String userId);
}
