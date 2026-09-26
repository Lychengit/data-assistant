package com.djzy.assistant.agentstate;

import io.agentscope.core.state.State;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台自己的「轮次状态」在框架状态库里的样子（HITL 挂起 / 恢复用的那份快照）。
 *
 * <p>**为什么要有这个类**：框架的 {@code AgentStateStore} 只认 {@link State} 这个标记接口，
 * 而平台要存的是「某一轮跑到一半、等用户点确认」的快照（{@code Snapshot}）。把快照的字段
 * 原样摊在一份 State 文档里，它就能和会话状态共用同一张表、同一套寻址、同一套 CAS，
 * 不必再为「平台自己的状态」单独养一套存取代码。
 *
 * <p>**字段为什么这么排**：前四个字段原样对应 {@code Snapshot}（运行时标识 / 版本 / 会话 / 生成时刻），
 * {@code payload} 是运行时自己理解的键值对——平台不解释它，只负责原样存取。
 *
 * <p>**可序列化要求**：框架用 Jackson 把 State 存成 JSON 再读回来，所以这个类必须有
 * 无参构造 + getter/setter（不要改成不可变对象）。
 */
public final class PlatformTurnState implements State {

    /** 哪个运行时写的（§19.14：跨运行时不允许恢复同一会话，比对不一致就拒绝）。 */
    private String runtimeId;

    private String runtimeVersion;

    private String sessionId;

    /** 快照生成时刻（epoch 毫秒，由运行时给出）。 */
    private long createdAtEpochMs;

    /** 运行时自己理解的载荷，平台原样存取、不解释。 */
    private Map<String, Object> payload = new LinkedHashMap<>();

    public PlatformTurnState() {}

    public PlatformTurnState(
            String runtimeId,
            String runtimeVersion,
            String sessionId,
            long createdAtEpochMs,
            Map<String, Object> payload) {
        this.runtimeId = runtimeId;
        this.runtimeVersion = runtimeVersion;
        this.sessionId = sessionId;
        this.createdAtEpochMs = createdAtEpochMs;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public String getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }

    public void setRuntimeVersion(String runtimeVersion) {
        this.runtimeVersion = runtimeVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public void setCreatedAtEpochMs(long createdAtEpochMs) {
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }
}
