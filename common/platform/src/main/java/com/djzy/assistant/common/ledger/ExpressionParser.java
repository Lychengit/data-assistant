package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 算式解析与复算（§6.3）：**不引入通用表达式引擎、不用 eval**，只认白名单算子。
 *
 * <p>算子白名单：{@code + - × ÷}、求和 / 平均 / 最大 / 最小 / 计数、占比、环比、同比、差额、百分点差。
 * 白名单以外一律拒绝。字面常量默认拒绝，只有字典声明的常量（如百分比换算的 100）可放行。
 *
 * <p>边界（§6.3）：分母为 0 / 数据缺失 → 抛 {@link ArithmeticException}，由调用方输出「无法计算」，
 * 禁止 {@code ∞ / -∞ / NaN / --}。
 */
public final class ExpressionParser {

    /** 函数白名单：名称 → 元数校验。 */
    private static final Map<String, java.util.function.IntPredicate> FUNCTIONS =
            Map.of(
                    "SUM", n -> n >= 1,
                    "AVG", n -> n >= 1,
                    "MAX", n -> n >= 1,
                    "MIN", n -> n >= 1,
                    "COUNT", n -> n >= 1,
                    "RATIO", n -> n == 2,
                    "YOY", n -> n == 2,
                    "MOM", n -> n == 2,
                    "DIFF", n -> n == 2,
                    "PP", n -> n == 2);

    /**
     * 算子白名单（**唯一来源**）：口径字典 {@code derived_of.operator} 与算式复算共用这一份，
     * 避免「配置允许、复算不认」的口径漂移（§6.1 / §6.3）。
     */
    public static final java.util.Set<String> ALLOWED_OPERATORS = FUNCTIONS.keySet();

    public static boolean isAllowedOperator(String name) {
        return name != null && ALLOWED_OPERATORS.contains(name.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);

    private final String text;
    private final Set<BigDecimal> allowedConstants;
    private int pos;

    private ExpressionParser(String text, Set<BigDecimal> allowedConstants) {
        this.text = text;
        this.allowedConstants = allowedConstants;
    }

    /** 解析并求值；{@code refResolver} 从台账取真值，取不到即抛异常。 */
    public static BigDecimal evaluate(String text, Set<BigDecimal> allowedConstants, Function<String, BigDecimal> refResolver) {
        ExpressionParser parser = new ExpressionParser(text == null ? "" : text, allowedConstants == null ? Set.of() : allowedConstants);
        BigDecimal value = parser.parseExpression(refResolver);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new ExpressionParseException("算式尾部存在无法解析的内容：" + parser.text.substring(parser.pos));
        }
        return value;
    }

    /** 抽取算式引用的全部台账编号（顺序去重）。 */
    public static List<String> refsUsed(String text) {
        List<String> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] tokens = (text == null ? "" : text).split("[^A-Za-z0-9_]+");
        for (String token : tokens) {
            if (isRef(token) && seen.add(token)) {
                refs.add(token);
            }
        }
        return refs;
    }

    private static boolean isRef(String token) {
        if (token.length() < 2) {
            return false;
        }
        char c = token.charAt(0);
        if (!(c == 'A' || c == 'a')) {
            return false;
        }
        for (int i = 1; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal parseExpression(Function<String, BigDecimal> refResolver) {
        BigDecimal left = parseTerm(refResolver);
        while (true) {
            skipWhitespace();
            if (consume('+')) {
                left = left.add(parseTerm(refResolver), MATH_CONTEXT);
            } else if (consume('-') || consume('−')) {
                left = left.subtract(parseTerm(refResolver), MATH_CONTEXT);
            } else {
                return left;
            }
        }
    }

    private BigDecimal parseTerm(Function<String, BigDecimal> refResolver) {
        BigDecimal left = parseUnary(refResolver);
        while (true) {
            skipWhitespace();
            if (consume('*') || consume('×') || consume('·')) {
                left = left.multiply(parseUnary(refResolver), MATH_CONTEXT);
            } else if (consume('/') || consume('÷')) {
                BigDecimal divisor = parseUnary(refResolver);
                if (divisor.signum() == 0) {
                    throw new ArithmeticException("分母为 0，无法计算");
                }
                left = left.divide(divisor, MATH_CONTEXT);
            } else {
                return left;
            }
        }
    }

    private BigDecimal parseUnary(Function<String, BigDecimal> refResolver) {
        skipWhitespace();
        if (consume('-') || consume('−')) {
            return parseUnary(refResolver).negate();
        }
        if (consume('+')) {
            return parseUnary(refResolver);
        }
        return parsePrimary(refResolver);
    }

    private BigDecimal parsePrimary(Function<String, BigDecimal> refResolver) {
        skipWhitespace();
        if (consume('(')) {
            BigDecimal value = parseExpression(refResolver);
            skipWhitespace();
            if (!consume(')')) {
                throw new ExpressionParseException("括号不匹配");
            }
            return value;
        }
        if (!atEnd() && (Character.isDigit(current()) || current() == '.')) {
            return parseNumber();
        }
        if (!atEnd() && Character.isLetter(current())) {
            String identifier = parseIdentifier();
            skipWhitespace();
            if (consume('(')) {
                List<BigDecimal> args = parseArguments(refResolver);
                return applyFunction(identifier, args);
            }
            if (isRef(identifier)) {
                BigDecimal value = refResolver.apply(identifier.toUpperCase(Locale.ROOT));
                if (value == null) {
                    throw new ExpressionParseException("引用不存在的台账编号：" + identifier);
                }
                return value;
            }
            throw new ExpressionParseException("白名单以外的标识符：" + identifier);
        }
        throw new ExpressionParseException("表达式不完整");
    }

    private List<BigDecimal> parseArguments(Function<String, BigDecimal> refResolver) {
        List<BigDecimal> args = new ArrayList<>();
        skipWhitespace();
        if (consume(')')) {
            return args;
        }
        while (true) {
            args.add(parseExpression(refResolver));
            skipWhitespace();
            if (consume(',')) {
                continue;
            }
            if (consume(')')) {
                return args;
            }
            throw new ExpressionParseException("函数参数分隔符不合法");
        }
    }

    private BigDecimal applyFunction(String name, List<BigDecimal> args) {
        String fn = name.toUpperCase(Locale.ROOT);
        java.util.function.IntPredicate arity = FUNCTIONS.get(fn);
        if (arity == null) {
            throw new ExpressionParseException("白名单以外的函数：" + name);
        }
        if (!arity.test(args.size())) {
            throw new ExpressionParseException("函数 " + fn + " 的参数个数不合法：" + args.size());
        }
        return switch (fn) {
            case "SUM" -> args.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MATH_CONTEXT));
            case "AVG" -> args.stream()
                    .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MATH_CONTEXT))
                    .divide(BigDecimal.valueOf(args.size()), MATH_CONTEXT);
            case "MAX" -> args.stream().max(BigDecimal::compareTo).orElseThrow();
            case "MIN" -> args.stream().min(BigDecimal::compareTo).orElseThrow();
            case "COUNT" -> BigDecimal.valueOf(args.size());
            case "RATIO" -> divideOrFail(args.get(0), args.get(1));
            case "YOY", "MOM" -> divideOrFail(args.get(0).subtract(args.get(1), MATH_CONTEXT), args.get(1));
            case "DIFF" -> args.get(0).subtract(args.get(1), MATH_CONTEXT);
            case "PP" -> args.get(0).subtract(args.get(1), MATH_CONTEXT).multiply(BigDecimal.valueOf(100), MATH_CONTEXT);
            default -> throw new ExpressionParseException("白名单以外的函数：" + name);
        };
    }

    private static BigDecimal divideOrFail(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("分母为 0，无法计算");
        }
        return numerator.divide(denominator, MATH_CONTEXT);
    }

    private BigDecimal parseNumber() {
        int start = pos;
        while (!atEnd() && (Character.isDigit(current()) || current() == '.')) {
            pos++;
        }
        String literal = text.substring(start, pos);
        BigDecimal value;
        try {
            value = new BigDecimal(literal);
        } catch (NumberFormatException e) {
            throw new ExpressionParseException("数字字面量不合法：" + literal);
        }
        if (!allowedConstants.contains(value)) {
            throw new ExpressionParseException("不能出现自由字面数字常量：" + literal + "（应引用台账编号）");
        }
        return value;
    }

    private String parseIdentifier() {
        int start = pos;
        while (!atEnd() && (Character.isLetterOrDigit(current()) || current() == '_')) {
            pos++;
        }
        return text.substring(start, pos);
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(current())) {
            pos++;
        }
    }

    private boolean consume(char c) {
        if (!atEnd() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private char current() {
        return text.charAt(pos);
    }

    private boolean atEnd() {
        return pos >= text.length();
    }
}
