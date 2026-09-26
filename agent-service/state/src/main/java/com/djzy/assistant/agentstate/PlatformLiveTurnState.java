package com.djzy.assistant.agentstate;

import io.agentscope.core.state.State;

/**
 * 「这个会话现在这一轮跑到哪了」的开始标记（T1-08）。
 *
 * <p>**为什么必须有这么一个东西**：框架只在**一轮调用结束时**才把会话状态写进库（L-01）。
 * 实例被硬杀（OOM / SIGKILL）或者机器直接断电时，这一轮就什么都没有留下：
 * 用户明明问过、界面也转过圈，历史里却像什么都没发生——用户唯一能得到的解释是「agent 忘了」。
 * 所以平台在**开跑之前**先写一个小标记，跑完再删掉；下次读历史时看到「有标记、但没人跑」，
 * 就能如实告诉用户「上一轮没跑完，请重新发送」。
 *
 * <p>**为什么标记里要带上用户原话**：因为框架没来得及存，原话只在平台的事件事实表里，
 * 而界面要在气泡里把问题显示出来。存一份原话（几十个字）是最便宜的解法。
 *
 * <p>**可序列化要求**：框架用 Jackson 把 State 存成 JSON 再读回来，所以这个类必须有
 * 无参构造 + getter/setter（不要改成不可变对象）。
 */
public final class PlatformLiveTurnState implements State {

    /** 轮次 id。 */
    private String turnId;

    /** 这一轮是在哪台实例上跑的（排障用：能立刻分清「跑到一半挂了」和「还在跑」）。 */
    private String instanceId;

    /** 用户这一轮问的原话。 */
    private String text;

    /** 开跑时刻（epoch 毫秒）。 */
    private long startedAtMs;

    public PlatformLiveTurnState() {}

    public PlatformLiveTurnState(String turnId, String instanceId, String text, long startedAtMs) {
        this.turnId = turnId;
        this.instanceId = instanceId;
        this.text = text;
        this.startedAtMs = startedAtMs;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public void setStartedAtMs(long startedAtMs) {
        this.startedAtMs = startedAtMs;
    }
}
