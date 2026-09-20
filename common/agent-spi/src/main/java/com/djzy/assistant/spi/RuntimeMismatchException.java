package com.djzy.assistant.spi;

/** 跨运行时恢复被拒绝（§19.14 fail-closed）：宁可拒绝恢复，也不接受语义错乱的「假成功」。 */
public class RuntimeMismatchException extends RuntimeException {

    private final String expectedRuntimeId;
    private final String actualRuntimeId;

    public RuntimeMismatchException(String sessionId, String expectedRuntimeId, String actualRuntimeId) {
        super("会话 " + sessionId + " 绑定的运行时为 " + actualRuntimeId + "，当前运行时为 " + expectedRuntimeId + "，拒绝恢复");
        this.expectedRuntimeId = expectedRuntimeId;
        this.actualRuntimeId = actualRuntimeId;
    }

    public String expectedRuntimeId() {
        return expectedRuntimeId;
    }

    public String actualRuntimeId() {
        return actualRuntimeId;
    }
}
