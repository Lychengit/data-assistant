package com.djzy.assistant.runtime.agentscope;

/**
 * 登记一次性确认凭据（§19.9 / §18.4.5 W1）：**用户批准之后**由平台落库，网关随后消费掉。
 *
 * <p><b>为什么要有这个口子</b>：网关 G3 对 {@code kind=write} 的接口要求带一个 {@code confirmId}
 * （必须属于该用户、未过期、一次性），而「用户点了确认」这件事只有平台知道。框架自己的那套确认
 * （{@code REQUIRE_USER_CONFIRM} → 用户点按钮 → 回调里带确认结果）解决的是**要不要执行这个工具调用**，
 * 它给的 id 不是网关认的那张凭据——两者对不上，写操作就会在网关 403
 * （审计里的 reason = {@code CONFIRM_REQUIRED}，2026-09-27 实测）。
 *
 * <p>所以这里把「批准 → 落一条凭据」接起来：{@link AgentscopeRuntimeAdapter#confirm} 在批准时登记，
 * 凭证 id 挂在这一轮上，工具调用把它带给网关。
 *
 * <p><b>登记失败就返回 {@code null}</b>——那就还是过不了确认闸口。宁可让这一轮失败，
 * 也不能凭空造一个「已确认」（§19.9 不可绕过）。
 */
@FunctionalInterface
public interface ConfirmRegistrar {

    /**
     * 登记一条待确认记录并直接置为「已批准」（用户刚刚点了确认）。
     *
     * @param summary 给审计与界面看的一句话：确认的是什么操作
     * @return 凭据 id；登记不了返回 {@code null}
     */
    String register(String userId, String sessionId, String turnId, String summary);

    /** 不装：不登记任何凭据（写操作照旧过不了网关的确认闸口，行为与改造前一致）。 */
    ConfirmRegistrar NONE = (userId, sessionId, turnId, summary) -> null;
}