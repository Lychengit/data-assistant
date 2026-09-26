package com.djzy.assistant.spi;

import org.reactivestreams.Publisher;

/**
 * agent 运行时端口（§18.10 / §18.11.5）——平台 core 只认这个接口，不认识任何框架。
 *
 * <p>不变量（§18.11.4）：
 * <ul>
 *   <li>运行时不得持有数据面凭据、不得直连网关；工具调用 100% 经 {@link com.djzy.assistant.spi.tool.ToolInvoker}；
 *   <li>事件 100% 以中立 {@link AgentEvent} 上报；
 *   <li>状态必须可导出为 {@link Snapshot}；
 *   <li>会话绑定 runtimeId / runtimeVersion，不允许跨运行时恢复（§19.14）。
 * </ul>
 */
public interface AgentRuntimePort {

    /** 运行时唯一标识，如 {@code agentscope} / {@code noop}；落 conversation_session.runtime_id。 */
    String id();

    /** 运行时版本；参与审计与跨运行时恢复校验（§19.14）。 */
    String version();

    /** 能力声明，用于框架特有增强的降级（ADR-32 ⑧）。 */
    RuntimeCapabilities capabilities();

    /** 开启一个运行时会话（不产生模型调用）。 */
    AgentSession start(AgentRunRequest request);

    /** 处理一轮用户输入，产出中立事件流（由框架 typed event 翻译而来）。 */
    Publisher<AgentEvent> stream(AgentSession session, AgentTurn turn);

    /** HITL 确认结果回灌（§19.9）。 */
    void confirm(AgentSession session, ConfirmDecision decision);

    /** 取消 / 停止：取消后不得再产生任何工具调用与模型调用（TCK-4）。 */
    void cancel(AgentSession session, String reason);

    /** 导出可恢复快照；实现必须把框架状态转换为中立负载（ADR-32 ⑥）。 */
    Snapshot snapshot(AgentSession session);

    /**
     * 从快照恢复会话。若 {@code snapshot.runtimeId()} 与当前运行时不一致，必须抛
     * {@link RuntimeMismatchException}（fail-closed，§19.14）。
     */
    AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request);

    /**
     * 一轮结束后释放本实例为这个会话留下的临时开销（**不影响持久化状态，也不等于关闭会话**）。
     *
     * <p>为什么单开一个方法而不是复用 {@link #close(AgentSession)}：两者的语义完全不同。
     * {@code close} 是「这个会话句柄我不要了」；而「一轮跑完了」是**每轮都会发生**的事——
     * 框架的共享 agent 会按 {@code (userId, sessionId)} 缓存会话状态与权限引擎，
     * 这些表没有上限，不清就等于「访问过的会话数」决定内存占用（DR-22）。
     * 把它放在 close 里，调用方要么每轮 close（语义错、还可能把共享 agent 关掉），
     * 要么永远不清（内存无界），两条路都是错的。
     *
     * <p>默认空实现：不支持这个概念的运行时（如测试替身 noop）不用改代码。
     */
    default void release(AgentSession session) {
        // 默认什么都不做
    }

    /** 释放会话资源（不删除持久化状态）。 */
    void close(AgentSession session);
}
