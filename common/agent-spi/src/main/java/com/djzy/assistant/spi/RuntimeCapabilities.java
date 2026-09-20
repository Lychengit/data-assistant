package com.djzy.assistant.spi;

/**
 * 运行时能力声明（ADR-32 ⑧）：框架特有增强按能力声明降级，不做最小公分母抽象。
 */
public record RuntimeCapabilities(
        boolean streaming,
        boolean hitl,
        boolean cancel,
        boolean snapshotResume,
        boolean subagents,
        boolean planMode,
        boolean externalExecution) {

    public static RuntimeCapabilities none() {
        return new RuntimeCapabilities(false, false, false, false, false, false, false);
    }

    public static RuntimeCapabilities full() {
        return new RuntimeCapabilities(true, true, true, true, true, true, true);
    }
}
