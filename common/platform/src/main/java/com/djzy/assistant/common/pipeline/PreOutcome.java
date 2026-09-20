package com.djzy.assistant.common.pipeline;

import java.util.Map;
import java.util.Optional;

/**
 * PRE 段结论（§9.5）：放行 / 拒绝 / 发起询问（ASK）/ 改写参数。
 *
 * <p>PRE **不保证拦住**——后面还有 GUARD。
 */
public record PreOutcome(Kind kind, String reason, String confirmId, String confirmSummary, Map<String, Object> rewrittenArguments) {

    public enum Kind {
        CONTINUE,
        DENY,
        ASK
    }

    public static PreOutcome proceed() {
        return new PreOutcome(Kind.CONTINUE, null, null, null, null);
    }

    public static PreOutcome deny(String reason) {
        return new PreOutcome(Kind.DENY, reason, null, null, null);
    }

    public static PreOutcome ask(String confirmId, String summary) {
        return new PreOutcome(Kind.ASK, "CONFIRM_REQUIRED", confirmId, summary, null);
    }

    public static PreOutcome rewrite(Map<String, Object> arguments) {
        return new PreOutcome(Kind.CONTINUE, null, null, null, Map.copyOf(arguments));
    }

    public boolean denied() {
        return kind == Kind.DENY;
    }

    public boolean asking() {
        return kind == Kind.ASK;
    }

    public Optional<Map<String, Object>> rewrittenIfPresent() {
        return Optional.ofNullable(rewrittenArguments);
    }
}
