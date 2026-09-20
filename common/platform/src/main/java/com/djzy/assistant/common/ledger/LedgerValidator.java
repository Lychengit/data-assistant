package com.djzy.assistant.common.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数字一致性校验入口（§6.3）：格式化节点在 LLM 产出最终答案后调用，快慢路径共用。
 *
 * <p>处置分级（不硬拦、不静默改数）：
 * <ol>
 *   <li>没交算式 / 格式不合规 → 让模型补一次；
 *   <li>复算对不上 / 引用不存在编号 / 有覆盖不到的数字 → 降级为「接口原始数据 + 口径标注」，计一次
 *       {@code hallucination_blocked}；
 *   <li>补一次后仍不合规 → 同样降级；
 *   <li>纯定性回答（无数字）→ 放行，计入「无数字断言占比」。
 * </ol>
 */
public final class LedgerValidator {

    private final ExpressionVerifier expressionVerifier;
    private final NumberAssertionScanner scanner;

    public LedgerValidator(ExpressionVerifier expressionVerifier, NumberAssertionScanner scanner) {
        this.expressionVerifier = expressionVerifier;
        this.scanner = scanner;
    }

    public LedgerValidator(int roundingScale, Set<String> ignoredLiterals) {
        this(new ExpressionVerifier(roundingScale), new NumberAssertionScanner(ignoredLiterals));
    }

    public LedgerValidation validate(
            String answer, List<String> figureRefs, List<Expression> expressions, NumberLedger ledger) {
        List<String> reasons = new ArrayList<>();
        List<ExpressionCheck> checks = new ArrayList<>();
        List<Expression> exprs = expressions == null ? List.of() : expressions;
        List<String> refs = figureRefs == null ? List.of() : figureRefs;

        for (String ref : refs) {
            if (ledger == null || !ledger.contains(ref)) {
                reasons.add("UNKNOWN_FIGURE_REF:" + ref);
            }
        }
        for (Expression expression : exprs) {
            ExpressionCheck check = expressionVerifier.verify(expression, ledger);
            checks.add(check);
            if (!check.passed()) {
                reasons.add("EXPRESSION_" + check.reason() + ":" + check.exprId());
            }
        }
        List<String> uncovered = scanner.uncoveredNumbers(answer, ledger, exprs);
        if (!uncovered.isEmpty()) {
            reasons.add("UNCOVERED_NUMBERS:" + String.join(",", uncovered));
        }
        if (exprs.isEmpty()) {
            // 没有算式：纯定性放行；只要出现数字就判为无依据。
            if (uncovered.isEmpty() && (answer == null || scanner.uncoveredNumbers(answer, null, List.of()).isEmpty())) {
                return new LedgerValidation(VerificationOutcome.NO_DIGITS, checks, reasons, uncovered);
            }
            reasons.add("NO_EXPRESSIONS");
        }
        if (reasons.isEmpty()) {
            return new LedgerValidation(VerificationOutcome.PASS, checks, reasons, uncovered);
        }
        return new LedgerValidation(VerificationOutcome.DEGRADED, checks, reasons, uncovered);
    }
}
