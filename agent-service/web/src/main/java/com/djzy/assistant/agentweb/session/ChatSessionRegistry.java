package com.djzy.assistant.agentweb.session;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话句柄登记簿：把「会话号」映射到本实例正在照看的那个 {@link ChatSession}。
 *
 * <p>**它管的是进程内的东西，不是事实源**：会话本身存不存在、属于谁，看的是状态库
 * （见 {@link SessionCatalog}）。这里只回答「本实例现在手里有没有这个会话的活口」——
 * 有活口才能取消正在跑的那一轮、才能接着推事件；没有就现建一个空壳（历史仍旧从状态库读）。
 *
 * <p>为什么要有上限并主动淘汰：会话句柄是**纯内存**的，一个用户聊得越久、用户越多，它就越涨。
 * 淘汰只挑「没在跑、也不是最近用过」的，正在跑的那一轮永远不动；被淘汰的会话下次用到时
 * 会再建一个空壳（代价是丢掉那一轮的内存回放位，但历史仍在状态库里）。
 */
public final class ChatSessionRegistry {

    private final int replayBufferSize;
    private final int maxSessions;
    private final ConcurrentMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatSessionRegistry(int replayBufferSize, int maxSessions) {
        this.replayBufferSize = replayBufferSize;
        this.maxSessions = maxSessions <= 0 ? 1024 : maxSessions;
    }

    public ChatSession create(String userId) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(sessionId, userId, replayBufferSize);
        sessions.put(sessionId, session);
        evictIfNeeded();
        return session;
    }

    public ChatSession find(String sessionId, String userId) {
        ChatSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || !session.userId().equals(userId)) {
            return null;
        }
        return session;
    }

    /** 拿本实例的活口；没有就现建一个空壳（**不读任何存储**：历史走 {@link SessionCatalog}）。 */
    public ChatSession getOrCreate(String sessionId, String userId) {
        ChatSession existing = find(sessionId, userId);
        if (existing != null) {
            return existing;
        }
        ChatSession created = new ChatSession(sessionId, userId, replayBufferSize);
        ChatSession raced = sessions.putIfAbsent(sessionId, created);
        evictIfNeeded();
        return raced == null ? created : raced;
    }

    /** 观测用。 */
    public Map<String, ChatSession> snapshot() {
        return Map.copyOf(sessions);
    }

    /**
     * 超出上限时淘汰最久没人碰过的空闲会话。
     *
     * <p>「正在跑的一轮」是唯一的禁区：把它淘汰掉，用户点停止 / 点确认时就找不到运行时句柄了。
     */
    private void evictIfNeeded() {
        int excess = sessions.size() - maxSessions;
        if (excess <= 0) {
            return;
        }
        List<ChatSession> candidates = sessions.values().stream()
                .filter(session -> !session.running())
                .sorted(Comparator.comparingLong(ChatSession::lastTouchedMs))
                .limit(excess)
                .toList();
        candidates.forEach(session -> sessions.remove(session.sessionId(), session));
    }

    /** 当前在跑的会话（观测 / 测试用）。 */
    public Set<String> runningSessionIds() {
        return sessions.values().stream()
                .filter(ChatSession::running)
                .map(ChatSession::sessionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
