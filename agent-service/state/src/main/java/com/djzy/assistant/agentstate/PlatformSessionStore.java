package com.djzy.assistant.agentstate;

import io.agentscope.core.state.AgentStateStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 会话档案（建档 / 列表 / 归档）的读写口，落在框架状态库里（键 {@code platform_session}）。
 *
 * <p>**为什么挂在框架的 {@link AgentStateStore} 上、不另建一张表**：会话档案和会话状态是同一件事的两个面，
 * 寻址维度完全一样（用户 + 会话号）。共用一套存取，就少一套「谁先写、谁先删、两边怎么对账」的问题。
 *
 * <p>**为什么建档要真写一行**：不写的话，「这个会话存在」只能靠「它跑过至少一轮」来判定，
 * 于是新建但还没提问的会话在列表里会凭空消失、归档也会无处可放。写的是极小的一个文档（几个数字），
 * 而且一个会话只写一次。
 */
public final class PlatformSessionStore {

    /** 会话档案在状态表里的键。取固定值：一个会话只留一份档案。 */
    public static final String KEY = "platform_session";

    private final AgentStateStore store;

    public PlatformSessionStore(AgentStateStore store) {
        this.store = store;
    }

    /**
     * 建档（幂等）：已经建过就什么都不做，**不覆盖**原来的建档时刻。
     *
     * <p>用「只在新行上写」的 CAS（期望版本 0）而不是覆盖写：两台实例同时给同一个会话建档时，
     * 后到的那次会撞上已存在的行、被判定为冲突，于是最早的建档时刻留下来——
     * 这正是我们想要的语义（会话是什么时候建的，不会因为谁重连了一下就变）。
     */
    public void create(String userId, String sessionId, long createdAtMs) {
        if (store.exists(userId, sessionId)) {
            return;
        }
        store.saveIfVersion(userId, sessionId, KEY, new PlatformSessionState(sessionId, createdAtMs), 0L);
    }

    public Optional<PlatformSessionState> load(String userId, String sessionId) {
        return store.get(userId, sessionId, KEY, PlatformSessionState.class);
    }

    /**
     * 记一次「这个会话又有人问了一句」（标题 / 条数 / 最后提问时刻）。
     *
     * <p>为什么在**提问的那一刻**记，而不是等这一轮跑完再回读对话去算：
     * 此刻平台手上就有人能给出答案的全部事实——标题就是用户刚打的那句话，条数加一，
     * 时刻就是现在；回读对话则要读整段正文（原来列表变慢就是因为这个）。
     *
     * <p>档案缺失（升级前建的老会话）就先补一份：不能因为「这条会话是上一版建的」而丢掉这次记录。
     *
     * <p>**允许失败**：调用方（提问入口）不把它当关键路径——这一条写失败只会让列表里的标题/条数
     * 落后一轮，而把用户的提问挡在门外才是大事故。所以调用方拿到异常后只记日志。
     */
    public PlatformSessionState recordQuestion(String userId, String sessionId, String question, long nowMs) {
        PlatformSessionState meta = load(userId, sessionId)
                .orElseGet(() -> new PlatformSessionState(sessionId, nowMs));
        meta.applyQuestion(question, nowMs);
        store.save(userId, sessionId, KEY, meta);
        return meta;
    }

    /**
     * 归档 / 取消归档，返回归档后的档案。
     *
     * <p>没有档案的会话（比如升级前建的老会话）先补一份再改：归档是用户点出来的意图，
     * 不该因为「这条会话是上一版建的」就失败。
     */
    public PlatformSessionState setArchived(String userId, String sessionId, boolean archived, long nowMs) {
        PlatformSessionState meta = load(userId, sessionId)
                .orElseGet(() -> new PlatformSessionState(sessionId, nowMs));
        meta.setArchived(archived);
        meta.setArchivedAtMs(archived ? nowMs : 0L);
        store.save(userId, sessionId, KEY, meta);
        return meta;
    }

    /** 该用户建过的全部会话号（顺序不保证，调用方自己排）。 */
    public Set<String> sessionIds(String userId) {
        return new LinkedHashSet<>(store.listSessionIds(userId));
    }

    /** 该用户建过的全部会话档案；读不出档案的（老会话）跳过，交给调用方按会话状态兜底。 */
    public List<PlatformSessionState> loadAll(String userId) {
        List<PlatformSessionState> all = new ArrayList<>();
        for (String sessionId : sessionIds(userId)) {
            load(userId, sessionId).ifPresent(all::add);
        }
        return all;
    }
}
