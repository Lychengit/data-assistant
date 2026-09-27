package com.djzy.assistant.spi;

/**
 * 会话里挂着一张**没答复**的写操作确认卡，用户却继续往下提问（§19.9）。
 *
 * <p>为什么值得单独一个类型：框架对这种情况是**抛异常**的（"Agent is paused for
 * human-in-the-loop confirmation…"），接入层的兜底归一化会把它变成
 * {@code INTERNAL} + 「服务暂不可用，请稍后再试」——用户看到的是平台故障，
 * 实际是他自己少点了一次「确认」或「取消」，重试永远不会好（2026-09-27 实测）。
 *
 * <p>约束（同 {@link RuntimeMisconfiguredException}）：{@link #userMessage()} 必须是
 * 构造方自己写定的常量文本，不含任何运行期数据——它会被原样下发到前端。
 */
public class PendingConfirmationException extends IllegalStateException {

    /**
     * 唯一一份文案：{@code UnifiedErrors.CONFIRM_PENDING} 也引用它，避免同一句话在两处各写一遍。
     * 放在这里而不是 {@code common/platform}：运行时模块够不到那边的常量。
     */
    public static final String DEFAULT_USER_MESSAGE =
            "上一轮还有一次写操作在等您确认：请先点「确认」或「取消」，再继续提问";

    private final transient String userMessage;

    public PendingConfirmationException() {
        this(DEFAULT_USER_MESSAGE);
    }

    public PendingConfirmationException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    /** 可以直接给用户看的、能照着做的一步操作。 */
    public String userMessage() {
        return userMessage;
    }
}
