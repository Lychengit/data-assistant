package com.djzy.assistant.runtime.agentscope;

/**
 * 一次调用里「跟着这一次调用走」的东西，统一放在框架的 {@code RuntimeContext} 上。
 *
 * <p>**为什么要有这个类**：AgentScope 的会话状态是按 {@code (userId, sessionId)} 分段存的
 * （一个 agent 实例服务所有用户，这点是框架自己的设计：每次调用开始时它才把这一段的
 * 状态装进来、结束时写回去）。所以「这一次调用是谁问的、用哪个回调执行工具、系统提示词是什么」
 * 这些东西**不能存在 agent 对象上**——存上去就等于把某一次调用钉死在共享实例里，
 * 后面的调用会读到别人的值。
 *
 * <p>框架给的现成位置就是 {@code RuntimeContext}：调用方（我们）在发起一轮时把值放进去，
 * 框架会在整个调用链里原样带着它走，中间件与工具都拿得到。这里把键名收在一处，
 * 免得三个文件里各写一遍字符串。
 */
final class CallAttributes {

    /** 本轮的系统提示词（每轮现算：相对时间 + 本轮接口清单，见 SystemPromptComposer）。 */
    static final String SYSTEM_PROMPT = "sysPrompt";

    /** 平台的工具执行出口（{@code ToolInvoker}）——工具真正执行时要用。 */
    static final String TOOL_INVOKER = "toolInvoker";

    /** 请求 id（审计串联用）。 */
    static final String REQUEST_ID = "requestId";

    /** 绝对截止时间（毫秒），超时预算链的起点。 */
    static final String DEADLINE_EPOCH_MS = "deadlineEpochMs";

    /** 轮次 id。 */
    static final String TURN_ID = "turnId";

    /** 链路 id。 */
    static final String TRACE_ID = "traceId";

    /** 已确认项 id（写操作要带，一次性，§19.9）。 */
    static final String CONFIRM_ID = "confirmId";

    private CallAttributes() {}
}
