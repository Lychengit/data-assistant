package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单位归一（§6.3-4）：{@code 万元 vs 元}、{@code % vs 小数} 必须互认。
 *
 * <p>维度不同（金额 vs 比例 vs 计数）不做换算，直接判为不一致。
 */
public final class UnitConverter {

    public enum Dimension {
        CURRENCY,
        RATIO,
        COUNT,
        UNKNOWN
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final Set<String> CURRENCY = Set.of("元", "万元", "亿元", "rmb", "cny");
    private static final Set<String> RATIO = Set.of("ratio", "%", "百分比", "小数");
    private static final Set<String> COUNT = Set.of("人", "个", "次", "单", "件", "条", "台");

    /** 相对维度基准单位的换算系数（基准：金额=元，比例=ratio）。 */
    private static final Map<String, BigDecimal> FACTORS =
            Map.of(
                    "元", BigDecimal.ONE,
                    "万元", new BigDecimal("10000"),
                    "亿元", new BigDecimal("100000000"),
                    "ratio", BigDecimal.ONE,
                    "%", new BigDecimal("0.01"));

    private UnitConverter() {}

    public static Dimension dimensionOf(String unit) {
        String normalized = normalize(unit);
        if (CURRENCY.contains(normalized)) {
            return Dimension.CURRENCY;
        }
        if (RATIO.contains(normalized)) {
            return Dimension.RATIO;
        }
        if (COUNT.contains(normalized)) {
            return Dimension.COUNT;
        }
        return Dimension.UNKNOWN;
    }

    /** 维度基准单位。 */
    public static String baseUnit(Dimension dimension) {
        return switch (dimension) {
            case CURRENCY -> "元";
            case RATIO -> "ratio";
            case COUNT -> "个";
            case UNKNOWN -> "";
        };
    }

    /** 同维度换算；维度不同或单位不认识 → empty（视为不一致）。 */
    public static Optional<BigDecimal> convert(BigDecimal value, String fromUnit, String toUnit) {
        Dimension from = dimensionOf(fromUnit);
        Dimension to = dimensionOf(toUnit);
        if (from == Dimension.UNKNOWN || from != to) {
            return Optional.empty();
        }
        return toBase(value, fromUnit, from).flatMap(v -> fromBase(v, toUnit, to));
    }

    public static Optional<BigDecimal> toBase(BigDecimal value, String unit, Dimension dimension) {
        return convertWithFactor(value, unit, dimension, false);
    }

    public static Optional<BigDecimal> fromBase(BigDecimal value, String unit, Dimension dimension) {
        return convertWithFactor(value, unit, dimension, true);
    }

    private static Optional<BigDecimal> convertWithFactor(
            BigDecimal value, String unit, Dimension dimension, boolean invert) {
        if (dimension == Dimension.COUNT) {
            return Optional.of(value);
        }
        BigDecimal factor = FACTORS.get(normalize(unit));
        if (factor == null) {
            return Optional.empty();
        }
        return Optional.of(invert ? value.divide(factor, MC) : value.multiply(factor, MC));
    }

    private static String normalize(String unit) {
        if (unit == null) {
            return "";
        }
        String u = unit.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "万" -> "万元";
            case "percent", "pct" -> "%";
            case "cn¥", "￥", "¥" -> "元";
            default -> u;
        };
    }
}
