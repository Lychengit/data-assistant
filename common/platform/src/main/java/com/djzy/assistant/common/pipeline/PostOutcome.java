package com.djzy.assistant.common.pipeline;

import com.djzy.assistant.spi.tool.ToolInvocationResult;

/**
 * POST 段结论（§9.5）：接受 / 拦截 / 替换结果 / 追加提醒上下文。
 *
 * <p>硬约束：**不把失败改成成功**；不改「本次调用身份」。
 */
public record PostOutcome(Kind kind, ToolInvocationResult replacement, String reason, String appendedNote) {

    public enum Kind {
        ACCEPT,
        REPLACE,
        REJECT,
        APPEND_NOTE
    }

    public static PostOutcome accept() {
        return new PostOutcome(Kind.ACCEPT, null, null, null);
    }

    public static PostOutcome replace(ToolInvocationResult result) {
        return new PostOutcome(Kind.REPLACE, result, null, null);
    }

    public static PostOutcome reject(String reason) {
        return new PostOutcome(Kind.REJECT, null, reason, null);
    }

    public static PostOutcome appendNote(String note) {
        return new PostOutcome(Kind.APPEND_NOTE, null, null, note);
    }
}
