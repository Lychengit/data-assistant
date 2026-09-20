package com.djzy.assistant.agentweb.session;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话登记簿（§19.14：会话绑定 runtimeId；这里只管接入层状态）。
 *
 * <p>会话**只属于创建它的用户**：拿别人的 sessionId 一律当作不存在（404，不泄露存在性，§11.3）。
 * 进程重启后按会话号从回放位重建（{@link #getOrRestore}），这样刷新页面仍能接到之前的回答。
 */
public final class ChatSessionRegistry {

    private final SessionJournal journal;
    private final int replayBufferSize;
    private final ConcurrentMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatSessionRegistry(SessionJournal journal, int replayBufferSize) {
        this.journal = journal;
        this.replayBufferSize = replayBufferSize;
    }

    public ChatSession create(String userId) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(sessionId, userId, journal, replayBufferSize);
        sessions.put(sessionId, session);
        return session;
    }

    public ChatSession find(String sessionId, String userId) {
        ChatSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || !session.userId().equals(userId)) {
            return null;
        }
        return session;
    }

    /** 断线重连：内存里没有（进程重启过）时，按会话号从回放位重建。 */
    public ChatSession getOrRestore(String sessionId, String userId) {
        ChatSession existing = find(sessionId, userId);
        if (existing != null) {
            return existing;
        }
        if (sessionId == null || userId == null) {
            return null;
        }
        List<JournalRecord> records = journal.readAfter(userId, sessionId, -1);
        if (records.isEmpty()) {
            return null;
        }
        ChatSession restored = new ChatSession(sessionId, userId, journal, replayBufferSize);
        restored.restore(records);
        sessions.put(sessionId, restored);
        return restored;
    }

    /** 该用户的会话列表（按最后活动倒序，§19.4）；归属过滤在日志层做，这里不再筛一遍。 */
    public List<SessionSummary> list(String userId) {
        return journal.listSessions(userId);
    }

    /** 该会话在回放位里的全部记录（升序）。调用方**必须先确认归属**，否则等于绕过 §11.3。 */
    public List<JournalRecord> records(String userId, String sessionId) {
        return journal.readAfter(userId, sessionId, -1);
    }

    /** 观测用。 */
    public Map<String, ChatSession> snapshot() {
        return Map.copyOf(sessions);
    }
}
