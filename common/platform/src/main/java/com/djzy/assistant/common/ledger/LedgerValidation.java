package com.djzy.assistant.common.ledger;

import java.util.List;

/**
 * 一轮答案的数字一致性校验结论（落 {@code conversation_turn.verify_result}，§8.3）。
 *
 * @param outcome 通过 / 纯定性放行 / 降级
 * @param checks 每条算式的复算结论（展示「计算过程」用）
 * @param reasons 降级原因（进跑偏信号 §10.4）
 * @param uncoveredNumbers 无法追溯到台账或算式的数字
 */
public record LedgerValidation(
        VerificationOutcome outcome, List<ExpressionCheck> checks, List<String> reasons, List<String> uncoveredNumbers) {

    public LedgerValidation {
        checks = checks == null ? List.of() : List.copyOf(checks);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        uncoveredNumbers = uncoveredNumbers == null ? List.of() : List.copyOf(uncoveredNumbers);
    }

    public boolean degraded() {
        return outcome == VerificationOutcome.DEGRADED;
    }

    /** 是否计入 {@code hallucination_blocked}（§10.4）。 */
    public boolean countsAsHallucinationBlocked() {
        return outcome == VerificationOutcome.DEGRADED;
    }
}
