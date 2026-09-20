package com.djzy.assistant.common.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §6.2 / §6.3：台账编号 + 算式复算；不合规→降级，纯定性→放行。 */
class LedgerValidatorTest {

    private final LedgerValidator validator = new LedgerValidator(1, Set.of());

    @Test
    void recomputedExpressionPassesAndPercentDisplayIsAllowed() {
        NumberLedger ledger = ledgerProfits();
        Expression exp = new Expression(
                "E1", "(A1 - A2) / A2", List.of("A1", "A2"), new BigDecimal("-0.5"), "ratio", "-50%");

        LedgerValidation result = validator.validate("上月利润下降 50%", List.of("A1", "A2"), List.of(exp), ledger);

        assertEquals(VerificationOutcome.PASS, result.outcome());
        assertTrue(result.checks().get(0).passed());
    }

    @Test
    void wrongResultIsDegradedNotSilentlyFixed() {
        NumberLedger ledger = ledgerProfits();
        Expression exp = new Expression(
                "E1", "(A1 - A2) / A2", List.of("A1", "A2"), new BigDecimal("-0.6"), "ratio", "-60%");

        LedgerValidation result = validator.validate("利润下降 60%", List.of(), List.of(exp), ledger);

        assertEquals(VerificationOutcome.DEGRADED, result.outcome());
        assertTrue(result.reasons().stream().anyMatch(r -> r.startsWith("EXPRESSION_MISMATCH")));
        assertTrue(result.countsAsHallucinationBlocked());
    }

    @Test
    void freeLiteralConstantInExpressionIsRejected() {
        NumberLedger ledger = ledgerProfits();
        Expression exp = new Expression("E1", "A1 * 2", List.of("A1"), new BigDecimal("200"), "万元", "200");

        LedgerValidation result = validator.validate("两倍", List.of(), List.of(exp), ledger);

        assertEquals(VerificationOutcome.DEGRADED, result.outcome());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("PARSE_ERROR")));
    }

    @Test
    void unknownLedgerRefIsDegraded() {
        NumberLedger ledger = ledgerProfits();
        Expression exp = new Expression("E1", "A9 + A1", List.of("A9"), new BigDecimal("1"), "万元", null);

        LedgerValidation result = validator.validate("合计", List.of(), List.of(exp), ledger);

        assertEquals(VerificationOutcome.DEGRADED, result.outcome());
    }

    @Test
    void unitConversionBetweenWanYuanAndYuanPasses() {
        NumberLedger ledger = new NumberLedger();
        String ref = ledger.register(new BigDecimal("10000"), "元", "profit");
        Expression exp = new Expression("E1", ref, List.of(ref), new BigDecimal("1"), "万元", "1.0");

        LedgerValidation result = validator.validate("利润 1 万元", List.of(ref), List.of(exp), ledger);

        assertEquals(VerificationOutcome.PASS, result.outcome());
    }

    @Test
    void divisionByZeroIsNotComputable() {
        NumberLedger ledger = new NumberLedger();
        String a1 = ledger.register(new BigDecimal("100"), "万元", "profit");
        String a2 = ledger.register(BigDecimal.ZERO, "万元", "cost");
        Expression exp = new Expression("E1", a1 + " / " + a2, List.of(a1, a2), new BigDecimal("0"), "ratio", null);

        LedgerValidation result = validator.validate("无法计算", List.of(), List.of(exp), ledger);

        assertEquals(VerificationOutcome.DEGRADED, result.outcome());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("NOT_COMPUTABLE")));
    }

    @Test
    void numberWithoutLedgerOrExpressionIsDegraded() {
        NumberLedger ledger = ledgerProfits();

        LedgerValidation result = validator.validate("上月利润 999 万元", List.of(), List.of(), ledger);

        assertEquals(VerificationOutcome.DEGRADED, result.outcome());
        assertTrue(result.uncoveredNumbers().contains("999"));
    }

    @Test
    void qualitativeAnswerWithoutNumbersIsAllowed() {
        LedgerValidation result = validator.validate("上月利润明显下降", List.of(), List.of(), ledgerProfits());

        assertEquals(VerificationOutcome.NO_DIGITS, result.outcome());
        assertFalse(result.countsAsHallucinationBlocked());
    }

    @Test
    void structuralNumbersLikeYearAndQuarterAreIgnored() {
        LedgerValidation result = validator.validate("2026 年第 2 季度利润与去年同期基本持平", List.of(), List.of(), ledgerProfits());

        assertEquals(VerificationOutcome.NO_DIGITS, result.outcome());
    }

    private static NumberLedger ledgerProfits() {
        NumberLedger ledger = new NumberLedger();
        ledger.register(new BigDecimal("100"), "万元", "profit", "2026-08", "利润·支付口径", "iface_profit", "100");
        ledger.register(new BigDecimal("200"), "万元", "profit", "2026-07", "利润·支付口径", "iface_profit", "200");
        return ledger;
    }
}
