package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 算式复算（§6.3）：解析 {@code text} → 从台账取真值 → BigDecimal 复算 → 与 {@code result} 比对。
 *
 * <p>容差 = 字典 {@code rounding} 精度下最后一位的半个单位（保留 1 位小数 → 容差 0.05）。
 * 单位先归一（{@code 万元 vs 元}、{@code % vs 小数} 必须互认）。
 */
public final class ExpressionVerifier {

    private final int roundingScale;
    private final Set<BigDecimal> dictionaryConstants;

    public ExpressionVerifier(int roundingScale, Set<BigDecimal> dictionaryConstants) {
        this.roundingScale = roundingScale;
        this.dictionaryConstants = dictionaryConstants == null ? Set.of() : Set.copyOf(dictionaryConstants);
    }

    public ExpressionVerifier(int roundingScale) {
        this(roundingScale, Set.of(BigDecimal.valueOf(100)));
    }

    public ExpressionCheck verify(Expression expression, NumberLedger ledger) {
        if (expression.result() == null) {
            return new ExpressionCheck(expression.exprId(), false, null, null, null, "MISSING_RESULT");
        }
        BigDecimal computed;
        try {
            computed = ExpressionParser.evaluate(
                    expression.text(), dictionaryConstants, ref -> refValue(ledger, ref));
        } catch (ExpressionParseException e) {
            return new ExpressionCheck(expression.exprId(), false, null, expression.result(), null, "PARSE_ERROR:" + e.reason());
        } catch (ArithmeticException e) {
            return new ExpressionCheck(expression.exprId(), false, null, expression.result(), null, "NOT_COMPUTABLE");
        }
        List<LedgerEntry> refs = new ArrayList<>();
        for (String ref : ExpressionParser.refsUsed(expression.text())) {
            Optional<LedgerEntry> entry = ledger.get(ref.toUpperCase(java.util.Locale.ROOT));
            if (entry.isEmpty()) {
                return new ExpressionCheck(expression.exprId(), false, computed, expression.result(), null, "UNKNOWN_REF:" + ref);
            }
            refs.add(entry.get());
        }
        String computedUnit = commonUnit(refs, expression.unit());
        BigDecimal tolerance = BigDecimal.valueOf(5, roundingScale + 1);
        BigDecimal computedBase =
                UnitConverter.toBase(computed, computedUnit, UnitConverter.dimensionOf(computedUnit)).orElse(computed);
        Optional<BigDecimal> declaredBase =
                UnitConverter.toBase(expression.result(), expression.unit(), UnitConverter.dimensionOf(computedUnit));
        if (declaredBase.isEmpty() && !computedUnit.equals(expression.unit())) {
            return new ExpressionCheck(
                    expression.exprId(), false, computed, expression.result(), tolerance, "UNIT_MISMATCH");
        }
        BigDecimal declaredInBase = declaredBase.orElse(expression.result());
        BigDecimal toleranceBase =
                UnitConverter.toBase(tolerance, expression.unit(), UnitConverter.dimensionOf(computedUnit)).orElse(tolerance);
        BigDecimal diff = computedBase.subtract(declaredInBase).abs();
        if (diff.compareTo(toleranceBase) > 0) {
            return new ExpressionCheck(
                    expression.exprId(), false, computed, expression.result(), toleranceBase, "MISMATCH");
        }
        if (!displayMatches(expression, declaredInBase, toleranceBase, computedUnit)) {
            return new ExpressionCheck(
                    expression.exprId(), false, computed, expression.result(), toleranceBase, "DISPLAY_MISMATCH");
        }
        return new ExpressionCheck(expression.exprId(), true, computed, expression.result(), toleranceBase, null);
    }

    private boolean displayMatches(
            Expression expression, BigDecimal declaredInBase, BigDecimal toleranceBase, String computedUnit) {
        String display = expression.display();
        if (display == null || display.isBlank()) {
            return true;
        }
        boolean percentDisplay = display.trim().endsWith("%");
        String unit = percentDisplay ? "%" : expression.unit();
        BigDecimal displayValue = extractNumber(display);
        if (displayValue == null) {
            return true;
        }
        UnitConverter.Dimension dimension = UnitConverter.dimensionOf(computedUnit);
        BigDecimal declaredInDisplayUnit =
                UnitConverter.fromBase(declaredInBase, unit, dimension).orElse(declaredInBase);
        BigDecimal toleranceInDisplayUnit =
                UnitConverter.fromBase(toleranceBase, unit, dimension).orElse(toleranceBase);
        return displayValue.subtract(declaredInDisplayUnit).abs().compareTo(toleranceInDisplayUnit) <= 0;
    }

    private static BigDecimal extractNumber(String display) {
        StringBuilder sb = new StringBuilder();
        for (char c : display.toCharArray()) {
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
                sb.append(c);
            }
        }
        if (sb.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal refValue(NumberLedger ledger, String ref) {
        LedgerEntry entry = ledger.get(ref).orElse(null);
        return entry == null ? null : convertToBase(entry);
    }

    private static BigDecimal convertToBase(LedgerEntry entry) {
        return UnitConverter.toBase(entry.value(), entry.unit(), UnitConverter.dimensionOf(entry.unit()))
                .orElse(entry.value());
    }

    private static String commonUnit(List<LedgerEntry> refs, String declaredUnit) {
        if (refs.isEmpty()) {
            return declaredUnit == null ? "" : declaredUnit;
        }
        String first = refs.get(0).unit();
        UnitConverter.Dimension dimension = UnitConverter.dimensionOf(first);
        for (LedgerEntry e : refs) {
            if (UnitConverter.dimensionOf(e.unit()) != dimension && dimension != UnitConverter.Dimension.UNKNOWN) {
                return UnitConverter.baseUnit(dimension);
            }
        }
        return UnitConverter.baseUnit(dimension);
    }

    static BigDecimal round(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    static MathContext mathContext() {
        return new MathContext(16, RoundingMode.HALF_UP);
    }
}
