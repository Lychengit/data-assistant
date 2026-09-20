package com.djzy.assistant.spi;

/**
 * 中立事件类型（§8.4 / ADR-27 ② / ADR-32）。
 *
 * <p>框架专有事件类型不得直接进入本枚举；框架 typed event 必须先翻译成这里的中立类型，
 * 再分两路：① append-only 日志与落库；② SSE 业务投影（§12.2）。
 */
public enum AgentEventType {
    /** 一轮开始。 */
    TURN_START,
    /**
     * 用户这一轮说了什么（平台侧在发起轮次时发出，不是运行时事件）。
     *
     * <p>它必须存在，否则三件事同时坏掉：① `conversation_turn.user_input` 永远是空；
     * ② 历史会话列表没有标题（只剩一串 sessionId）；③ 违反 ADR-28 ③「模型可见即落库」——
     * 用户的提问确实进了模型请求，却无法从事务日志重建。
     */
    USER_MESSAGE,
    /** 思考流增量（§20.6，默认档不下发内容）。 */
    THOUGHT_DELTA,
    /** 思考块结束。 */
    THOUGHT,
    /** 正文流式增量。 */
    TEXT_DELTA,
    /** 完整文本块（非流式汇总）。 */
    TEXT,
    /** 工具调用开始。 */
    TOOL_CALL_START,
    /** 工具调用参数增量。 */
    TOOL_CALL_ARGS_DELTA,
    /** 工具调用结束（即将执行）。 */
    TOOL_CALL_END,
    /** 工具结果。 */
    TOOL_RESULT,
    /** 需要用户确认（HITL，§19.9）。 */
    AWAITING_CONFIRM,
    /** 确认结果回灌。 */
    CONFIRM_RESULT,
    /** 需要外部执行（技能 / 沙箱通道，§9.5 通道适配器）。 */
    AWAITING_EXTERNAL_EXECUTION,
    /** 外部执行结果。 */
    EXTERNAL_EXECUTION_RESULT,
    /** 子 agent 开始 / 结束（source 标识归属）。 */
    SUBAGENT_START,
    SUBAGENT_END,
    /** 全部工具被拒绝。 */
    ALL_TOOLS_DENIED,
    /** 超过最大迭代步数。 */
    EXCEED_MAX_ITERS,
    /** 用户请求停止。 */
    REQUEST_STOP,
    /** 业务自定义事件（skill_start / artifact / sandbox_job 等，ADR-27 ②）。 */
    CUSTOM,
    /** 归一化错误（不泄露内部堆栈，TCK-12）。 */
    ERROR,
    /**
     * 会话被归档 / 取消归档（平台侧动作，不属于任何一轮）。
     *
     * <p>为什么它必须是一条**事件**而不是一张「会话目录表」里的一个字段：归档是发生过的事，
     * 而事实源就这一条顺序日志（ADR-28）。写进日志，重启、回放、跨实例读到的都是同一份；
     * 另建一张表就等于给会话状态安了第二个真相，谁也说不清哪个先坏。
     */
    SESSION_ARCHIVED,
    /** 一轮结束。 */
    TURN_END
}
