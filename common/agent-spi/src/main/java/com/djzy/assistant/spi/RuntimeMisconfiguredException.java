package com.djzy.assistant.spi;

/**
 * 运行时「配置缺失」这一类**可自解释**的失败（区别于一肚子内部细节的真故障）。
 *
 * <p>为什么值得单独一个类型：接入层默认把运行时的任何异常都归一化成
 * {@code RUNTIME_UNAVAILABLE} + 一句「服务暂不可用，请稍后再试」——这是对的，
 * 因为异常文本可能带内部细节（§TCK-12）。但「还没配模型供应商」不是内部故障，
 * 而是**运维照着做一步就能好**的事：也归一化成「请稍后再试」，用户唯一能做的就是反复重试。
 *
 * <p>约束（§20.1.6 硬约束第 7 条）：{@link #userMessage()} 必须是构造方**自己写定的常量文本**，
 * 不得拼接底层异常消息、API Key、URL、SQL 等任何运行期数据——它会被原样下发到前端。
 */
public class RuntimeMisconfiguredException extends IllegalStateException {

    private final transient String userMessage;

    public RuntimeMisconfiguredException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    /** 可以直接给用户看的、可执行的说明（不含任何运行期数据）。 */
    public String userMessage() {
        return userMessage;
    }
}