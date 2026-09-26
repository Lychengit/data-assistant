package com.djzy.assistant.agentstate;

import io.agentscope.core.state.AgentStateStore;
import java.util.Optional;

/**
 * 「当前这一轮」开始标记的读写口，落在框架状态库里（键 {@code platform_turn_live}，T1-08）。
 *
 * <p>与会话档案 / 挂起快照共用同一张表、同一套寻址（{@code (user_id, session_id, state_key)}），
 * 只是键不同、生命周期不同：这个标记**每条轮次都重写一次、跑完就删**，
 * 是三个平台侧键里唯一高频的一个。它就是一行小 JSON，代价可以忽略。
 *
 * <p>**为什么不用带 TTL 的 Redis 键**：这个标记的用途是「下次读历史时解释那一轮」，
 * 而读历史走的是 PG（历史正文也在 PG）。放在 Redis 里会遇到「Redis 淘汰了标记、正文还在」，
 * 于是又回到「静默丢失」；而且它与会话状态在同一个事务性存储里，语义一致、排障时一处就能查全。
 */
public final class PlatformLiveTurnStore {

    /** 当前轮次标记在状态表里的键。固定值：一个会话同时只有一轮。 */
    public static final String KEY = "platform_turn_live";

    private final AgentStateStore store;

    public PlatformLiveTurnStore(AgentStateStore store) {
        this.store = store;
    }

    /** 开跑前记一笔（覆盖上一次：上一次要么已经收尾，要么就是没跑完的那一轮，被这一次接管）。 */
    public void start(String userId, String sessionId, PlatformLiveTurnState state) {
        store.save(userId, sessionId, KEY, state);
    }

    public Optional<PlatformLiveTurnState> load(String userId, String sessionId) {
        return store.get(userId, sessionId, KEY, PlatformLiveTurnState.class);
    }

    /** 这一轮跑完了（或确认停掉了）就删掉：留着会让下一轮误以为「有人没跑完」。 */
    public void clear(String userId, String sessionId) {
        store.delete(userId, sessionId, KEY);
    }
}
