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

    /** 释放会话资源（不删除持久化状态）。 */
    void close(AgentSession session);
}
