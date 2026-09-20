package com.djzy.assistant.spi.tool;

/** 工具调用结果状态（中立，框架无关）。 */
public enum ToolInvocationStatus {
    /** 执行成功。 */
    OK,
    /** 被拒绝（权限 / 守卫 / 配额）。 */
    DENIED,
    /** 需要用户确认后才执行（HITL，§19.9）。 */
    AWAITING_CONFIRM,
    /** 需要外部执行（技能 / 沙箱通道，§9.5）。 */
    AWAITING_EXTERNAL_EXECUTION,
    /** 超时。 */
    TIMEOUT,
    /** 失败（下游错误 / 系统错误）。 */
    ERROR
}
