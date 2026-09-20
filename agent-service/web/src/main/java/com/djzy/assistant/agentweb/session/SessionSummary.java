package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEventType;
import java.util.List;

/**
 * 会话列表里的一行（§19.4 历史会话 / 断线续传用的是同一份数据）。
 *
 * <p>**没有「会话目录」表**：标题、轮次数、时间、归档位都从回放位（{@link SessionJournal}）派生。
 * 另建一张目录表就等于给同一件事两份真相——删会话、改标题、写失败都会开始打架，
 * 而日志本来就是唯一事实源（ADR-28：历史 / 回放 / 断线续传一律从日志派生）。
 *
 * @param sessionId 会话号
 * @param title 首条用户提问（截断到 {@value #TITLE_MAX} 字）；没问过就是「未命名会话」
 * @param questions 用户提问条数（≈ 轮次数；HITL 确认续跑不重复计数）
 * @param createdAtMs 首条记录时刻
 * @param lastActiveMs 末条记录时刻（列表按它倒序，最近聊过的在最上面）
 * @param archived 是否已归档（最后一次归档 / 取消归档说了算，没有那条记录就是没归档）
 * @param archivedAtMs 归档时刻；未归档为 0
 */
public record SessionSummary(
        String sessionId,
        String title,
        int questions,
        long createdAtMs,
        long lastActiveMs,
        boolean archived,
        long archivedAtMs) {

    /** 标题上限：再长也只是列表里的一行，截断比横向滚动友善。 */
    public static final int TITLE_MAX = 60;

    private static final String UNTITLED = "未命名会话";

    /** 从回放位记录派生一行摘要。 */
    public static SessionSummary from(String sessionId, List<JournalRecord> records) {
        Accumulator accumulator = new Accumulator();
        records.forEach(accumulator::accept);
        return accumulator.toSummary(sessionId);
    }

    /**
     * 边写边累积（{@link FileSessionJournal} 用）：每来一条事件就重扫整段记录会退化成 O(n²)。
     *
     * <p>折叠规则只此一份——两个日志实现共用同一个累积器，否则「文件里读出来的」和
     * 「内存里攒出来的」迟早对不上（这种不一致最难查：同一份日志，换个实现结论就变了）。
     */
    public static final class Accumulator {

        private String title;
        private int questions;
        private long createdAtMs;
        private long lastActiveMs;
        private boolean archived;
        private long archivedAtMs;

        public synchronized void accept(JournalRecord record) {
            if (createdAtMs == 0L || record.timestampMs() < createdAtMs) {
                createdAtMs = record.timestampMs();
            }
            lastActiveMs = Math.max(lastActiveMs, record.timestampMs());
            SseEventType type = record.event().type();
            if (type == SseEventType.USER) {
                questions++;
                if (title == null) {
                    Object text = record.event().payload().get("text");
                    if (text != null && !String.valueOf(text).isBlank()) {
                        title = String.valueOf(text);
                    }
                }
                return;
            }
            if (type == SseEventType.ARCHIVED) {
                // 后写的说了算：归档 → 取消归档 → 再归档，最后一次是什么就是什么
                archived = Boolean.TRUE.equals(record.event().payload().get("archived"));
                archivedAtMs = archived ? record.timestampMs() : 0L;
            }
        }

        public synchronized SessionSummary toSummary(String sessionId) {
            return new SessionSummary(
                    sessionId, title(title), questions, createdAtMs, lastActiveMs, archived, archivedAtMs);
        }
    }

    private static String title(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNTITLED;
        }
        // 先 strip 再压缩空白：用户在输入框里敲的前后空格不该出现在列表标题里
        String oneLine = raw.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_MAX ? oneLine : oneLine.substring(0, TITLE_MAX) + "…";
    }
}