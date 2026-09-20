package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字面数字检查（§6.3-5）：{@code answer} 中出现的数字必须来自某个台账编号或某个 {@code exprId} 的 display。
 *
 * <p>**不从自然语言里抠数字去比对**——这里只做「覆盖性检查」：
 * 「三甲」「第 2 季度」「2026 年」这类结构被显式忽略，避免误杀正当派生数字。
 * 两处都落不上 → 判定无依据，走降级。
 */
public final class NumberAssertionScanner {

    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)(%?)");
    private static final Pattern REF_TOKEN = Pattern.compile("[Aa]\\d+");
    private static final Set<String> DATE_SUFFIXES = Set.of("年", "月", "日", "号", "季度", "周", "期", "届");

    private final Set<String> ignoredLiterals;

    public NumberAssertionScanner(Set<String> ignoredLiterals) {
        this.ignoredLiterals = ignoredLiterals == null ? Set.of() : Set.copyOf(ignoredLiterals);
    }

    public NumberAssertionScanner() {
        this(Set.of());
    }

    /**
     * @return 无法追溯到台账编号或算式 display 的数字（原样文本，按出现顺序去重）
     */
    public List<String> uncoveredNumbers(String answer, NumberLedger ledger, List<Expression> expressions) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        String text = REF_TOKEN.matcher(answer).replaceAll(" ");
        Set<BigDecimal> covered = coveredValues(ledger, expressions);
        List<String> uncovered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(0);
            String digits = matcher.group(1);
            int start = matcher.start();
            int end = matcher.end();
            if (isStructural(text, start, end, digits)
                    || ignoredLiterals.contains(raw)
                    || ignoredLiterals.contains(digits)) {
                continue;
            }
            BigDecimal value = new BigDecimal(digits);
            if (isCovered(value, raw.endsWith("%"), covered)) {
                continue;
            }
            if (seen.add(raw)) {
                uncovered.add(raw);
            }
        }
        return uncovered;
    }

    /** 可追溯集合：台账真值 + 算式结果 + 算式 display（按绝对值比较，百分比形态互认）。 */
    private static Set<BigDecimal> coveredValues(NumberLedger ledger, List<Expression> expressions) {
        Set<BigDecimal> covered = new LinkedHashSet<>();
        if (ledger != null) {
            ledger.entries().values().forEach(e -> covered.add(e.value().abs()));
        }
        if (expressions != null) {
            for (Expression expression : expressions) {
                if (expression.result() != null) {
                    covered.add(expression.result().abs());
                    covered.add(expression.result().abs().movePointRight(2));
                }
                BigDecimal fromDisplay = numericPart(expression.display());
                if (fromDisplay != null) {
                    covered.add(fromDisplay.abs());
                }
            }
        }
        return covered;
    }

    private static boolean isCovered(BigDecimal value, boolean percentToken, Set<BigDecimal> covered) {
        BigDecimal absolute = value.abs();
        if (covered.contains(absolute)) {
            return true;
        }
        if (percentToken && covered.contains(absolute.movePointLeft(2))) {
            return true;
        }
        return covered.stream().anyMatch(v -> v.compareTo(absolute) == 0);
    }

    private static boolean isStructural(String text, int start, int end, String digits) {
        // 「第 2 季度」这类序数：向左跳过空白后若为「第」则忽略
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
            before--;
        }
        if (before >= 0 && text.charAt(before) == '第') {
            return true;
        }
        // 「2026 年」这类日期：向右跳过空白后检查后缀
        int after = end;
        while (after < text.length() && Character.isWhitespace(text.charAt(after))) {
            after++;
        }
        String suffixText = text.substring(after, Math.min(text.length(), after + 2));
        for (String suffix : DATE_SUFFIXES) {
            if (suffixText.startsWith(suffix)) {
                return true;
            }
        }
        return digits.length() == 4 && (digits.startsWith("19") || digits.startsWith("20"));
    }

    private static BigDecimal numericPart(String display) {
        if (display == null || display.isBlank()) {
            return null;
        }
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
            BigDecimal value = new BigDecimal(sb.toString());
            return display.trim().endsWith("%") ? value.movePointLeft(2) : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isPercentToken(String token) {
        return token != null && token.toUpperCase(Locale.ROOT).endsWith("%");
    }
}
