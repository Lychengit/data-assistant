package com.djzy.assistant.common.error;

/**
 * 统一错误措辞（§4.5 / §11.3）：医生不存在 / 无权查看 / 数据为空对外一律同一句话，防枚举探测。
 *
 * <p>差异只进审计日志（{@code permission_audit}），不下发前端。
 */
public final class UnifiedErrors {

    /** 医生不存在 / 无权查看 / 数据为空 的统一对外措辞。 */
    public static final String NOT_FOUND_OR_FORBIDDEN = "未找到您有权查看的相关数据";

    /** 服务间验签失败统一措辞（不区分「key 不存在」与「签名不对」，§20.1.2）。 */
    public static final String UNAUTHORIZED = "未认证的请求";

    /** 权限不足（无授权来源，fail-closed）。 */
    public static final String FORBIDDEN = "未找到您有权查看的相关数据";

    /** LLM 故障（§9.2）。 */
    public static final String LLM_UNAVAILABLE = "AI 服务暂不可用，请稍后再试";

    /** 超时（§9.1）。 */
    public static final String TIMEOUT = "处理超时，请重试或简化问题";

    /** 确认无法唯一绑定（§19.9）。 */
    public static final String CONFIRM_NOT_UNIQUE = "无法确定您确认的是哪一项操作，请重新选择";

    /**
     * 会话里还有一张**没答复**的写操作确认卡（§19.9）。
     *
     * <p>为什么必须单独一句话：框架对「有待批准的工具调用、这一轮却没给确认结果」是直接抛异常的，
     * 归一化之后就变成「服务暂不可用，请稍后再试」——用户唯一能做的就是重试，而重试永远不会好。
     * 实际原因是**用户自己有一步没做**（点确认 / 点取消），所以把这一步说出来（2026-09-27 实测）。
     */
    public static final String CONFIRM_PENDING = com.djzy.assistant.spi.PendingConfirmationException.DEFAULT_USER_MESSAGE;

    /** 限流（§9.3）。 */
    public static final String RATE_LIMITED = "请求过于频繁，请稍后再试";

    /** 入参不合法（§18.4.5 I3：400 统一措辞）。 */
    public static final String INVALID_REQUEST = "请求参数不正确，请检查后重试";

    /** 熔断 / 依赖不可用（§9.2）。 */
    public static final String SERVICE_UNAVAILABLE = "服务暂不可用，请稍后再试";

    /**
     * 未配置大模型（ADR-14）。
     * <p>和上面 {@code LLM_UNAVAILABLE} 故意分开：那句是「服务故障」（用户只能等），
     * 这句是「还没配」（等不来，必须有人去配），所以直接给出「去哪儿配」。
     */
    public static final String LLM_NOT_CONFIGURED = "尚未配置可用的大模型：请管理员在管理端「模型供应商」页配置并启用一个";

    private UnifiedErrors() {}
}
