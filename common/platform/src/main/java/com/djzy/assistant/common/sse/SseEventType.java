package com.djzy.assistant.common.sse;

/**
 * SSE 事件契约（§12.2，唯一事件契约源）。
 *
 * <p>本表是**业务投影层**：底层必须由框架 typed event 派生（先归一化为中立 {@code AgentEvent}），
 * 禁止另起一套事件总线。业务事件（skill_start / artifact / sandbox_job）用 {@code CUSTOM} 承载。
 */
public enum SseEventType {
    SESSION("session"),
    // 会话被归档 / 取消归档：它是会话级状态，不属于任何一轮，所以不进轮次分组（§19.4）
    ARCHIVED("archived"),
    // 用户自己的提问（平台侧发出，不是模型事件）：历史会话要能回放出「问了什么」，
    // 界面上它就是气泡左边那句；没有它，历史只剩答案、没有上下文。
    USER("user"),
    INTENT("intent"),
    PERMISSION("permission"),
    STEP("step"),
    SKILL_START("skill_start"),
    SKILL_STEP("skill_step"),
    TOOL("tool"),
    SANDBOX_JOB("sandbox_job"),
    ARTIFACT("artifact"),
    CONFIRM("confirm"),
    CLARIFY("clarify"),
    TOKEN("token"),
    FINAL("final"),
    ERROR("error"),
    DONE("done");

    private final String wireName;

    SseEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
