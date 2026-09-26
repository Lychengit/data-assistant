package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.agentstate.PlatformSessionState;

/**
 * 会话列表里的一行（§19.4 历史会话）。
 *
 * <p>**它是从「会话档案」派生出来的视图，不读对话正文**（这是 H-13 改的口径）。
 * 原来每开一次列表都要把每个会话的整段对话读出来，只为了算标题、条数与最后活动时刻——
 * 一个用户几百个会话就是几百次大对象读。现在那三个数在**提问的那一刻**就写进了档案
 * （见 {@code PlatformSessionStore#recordQuestion}），列表只读档案。
 *
 * <p>于是口径上有一条要记住：**这里是索引，不是真相**。对话正文的真相始终是框架的会话状态，
 * 历史回放（{@code /turns}）读的是它，一个字都不来自这里；档案落后（例如绕过平台直接往状态库
 * 塞过对话）时，列表显示的是档案里的旧值——用户再问一句就跟上了。
 *
 * @param sessionId 会话号
 * @param title 首条提问截断后的标题；没问过就是「未命名会话」
 * @param questions 提问条数（≈ 轮次数；HITL 确认续跑不重复计数）
 * @param createdAtMs 建档时刻
 * @param lastActiveMs 最后一次提问的时刻（列表按它倒序，最近聊过的在最上面）
 * @param archived 是否已归档
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

    private static final String UNTITLED = "未命名会话";

    /**
     * 由会话档案派生一行。
     *
     * @param meta 会话档案；为空（理论上只有「列表 iterating 时档案正好被删」才会遇到）时
     *     给一行「刚建、没问过」的空样子，而不是抛异常——列表少一行是小事，打不开列表是大事
     */
    public static SessionSummary of(String sessionId, PlatformSessionState meta) {
        if (meta == null) {
            return new SessionSummary(sessionId, UNTITLED, 0, 0L, 0L, false, 0L);
        }
        long createdAtMs = meta.getCreatedAtMs();
        // 没问过时 lastActiveMs 是 0：用它排序会跑到列表最底下，所以退回建档时刻
        long lastActiveMs = Math.max(meta.getLastActiveMs(), createdAtMs);
        return new SessionSummary(
                sessionId,
                title(meta.getTitle()),
                meta.getQuestions(),
                createdAtMs,
                lastActiveMs,
                meta.isArchived(),
                meta.getArchivedAtMs());
    }

    /** 档案里没标题（还没问过、或升级前的老会话）就用统一的占位语，别在前面留一片空白。 */
    private static String title(String stored) {
        return stored == null || stored.isBlank() ? UNTITLED : stored;
    }
}
