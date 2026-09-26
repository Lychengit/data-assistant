package com.djzy.assistant.agentstate;

import com.djzy.assistant.spi.Snapshot;
import io.agentscope.core.state.AgentStateStore;
import java.util.Optional;

/**
 * 平台的轮次状态读写口：把 {@link Snapshot} 存进框架的 {@link AgentStateStore}（键 {@code platform_turn}）。
 *
 * <p>**为什么不再自己造一个状态端口**：挂起 / 恢复要的寻址（userId + sessionId）、并发控制（版本 CAS）、
 * 会话清理，框架的状态库已经全部具备。平台再包一层「快照端口 + PG 实现 + 文件实现」，
 * 只是把同一件事实现三遍，而且两条链路很容易各写各的、对不上。这里只做一件事：
 * 把平台的快照**翻译**成框架认识的状态文档，存取都交给状态库。
 *
 * <p>**与聊天记录的分工**：这里存的是「什么时候可以继续跑」（可变、可覆盖），
 * 而不是聊天原文；聊天原文的权威在会话状态（{@code agent_state}）里，二者共用同一张表、不同的键。
 */
public final class PlatformTurnStore {

    /** 平台快照在这张表里的键。取固定值：一个会话只留一份「当前轮次」的快照。 */
    public static final String KEY = "platform_turn";

    private final AgentStateStore store;

    public PlatformTurnStore(AgentStateStore store) {
        this.store = store;
    }

    /** 取这个会话挂起时的快照；没挂起过就是空。 */
    public Optional<Snapshot> load(String userId, String sessionId) {
        return store.get(userId, sessionId, KEY, PlatformTurnState.class)
                .map(state -> new Snapshot(
                        state.getRuntimeId(),
                        state.getRuntimeVersion(),
                        state.getSessionId() == null ? sessionId : state.getSessionId(),
                        state.getCreatedAtEpochMs(),
                        state.getPayload()));
    }

    /** 覆盖写：挂起快照会随轮次推进被替换，不需要版本校验。 */
    public void save(String userId, String sessionId, Snapshot snapshot) {
        store.save(
                userId,
                sessionId,
                KEY,
                new PlatformTurnState(
                        snapshot.runtimeId(),
                        snapshot.runtimeVersion(),
                        snapshot.sessionId(),
                        snapshot.createdAtEpochMs(),
                        snapshot.payload()));
    }

    /** 本轮已经结束（或用户放弃）时清掉，免得下次误以为还在挂起。 */
    public void clear(String userId, String sessionId) {
        store.delete(userId, sessionId, KEY);
    }
}
