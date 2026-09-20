package com.djzy.assistant.agentweb.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 单实例内存实现（测试与无卷环境的退路；生产用 {@link FileSessionJournal}）。 */
public final class InMemorySessionJournal implements SessionJournal {

    private final ConcurrentMap<String, List<JournalRecord>> bySession = new ConcurrentHashMap<>();

    @Override
    public void append(String userId, String sessionId, JournalRecord record) {
        bySession.computeIfAbsent(key(userId, sessionId), key -> new ArrayList<>()).add(record);
    }

    @Override
    public List<JournalRecord> readAfter(String userId, String sessionId, long afterSeq) {
        return snapshot(key(userId, sessionId)).stream().filter(r -> r.seq() > afterSeq).toList();
    }

    @Override
    public long lastSeq(String userId, String sessionId) {
        return snapshot(key(userId, sessionId)).stream()
                .map(JournalRecord::seq)
                .max(Comparator.naturalOrder())
                .orElse(-1L);
    }

    @Override
    public List<SessionSummary> listSessions(String userId) {
        if (userId == null) {
            return List.of();
        }
        String prefix = userId + "\u0000";
        return bySession.keySet().stream()
                .filter(indexKey -> indexKey.startsWith(prefix))
                .map(indexKey -> SessionSummary.from(indexKey.substring(prefix.length()), snapshot(indexKey)))
                .sorted(Comparator.comparingLong(SessionSummary::lastActiveMs)
                        .reversed()
                        .thenComparing(SessionSummary::sessionId))
                .toList();
    }

    private List<JournalRecord> snapshot(String indexKey) {
        List<JournalRecord> all = bySession.getOrDefault(indexKey, List.of());
        synchronized (all) {
            return List.copyOf(all);
        }
    }

    private static String key(String userId, String sessionId) {
        return userId + "\u0000" + sessionId;
    }

    /** 观测用。 */
    public Map<String, Integer> sizes() {
        return bySession.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }
}
