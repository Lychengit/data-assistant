package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 数字台账条目（§6.2）：工具 / 技能返回的每个数字由程序编号登记。
 *
 * <p>编号（如 {@code A1}）是模型引用数字的**唯一合法方式**：模型不能自造字面数字，只能引用编号或写算式。
 *
 * @param ref 稳定编号
 * @param value 真值（接口返回的原始数值，BigDecimal）
 * @param unit 单位（如 {@code 元} / {@code 万元} / {@code ratio} / {@code %} / {@code 人}）
 * @param metricKey 口径 key（字典）
 * @param timeRange 时间口径（如 {@code 2026-07}、{@code 2026Q2}、{@code [2026-01-01,2026-02-01)}）
 * @param basis 口径说明（如「利润·支付口径」）
 * @param apiCode 来源接口（溯源标注用）
 * @param display 展示格式化文本（已按字典精度格式化）
 */
public record LedgerEntry(
        String ref,
        BigDecimal value,
        String unit,
        String metricKey,
        String timeRange,
        String basis,
        String apiCode,
        String display) {

    public LedgerEntry {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(value, "value");
        unit = unit == null ? "" : unit;
        display = display == null ? value.toPlainString() : display;
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "ref", ref,
                "value", value,
                "unit", unit,
                "metricKey", metricKey == null ? "" : metricKey,
                "timeRange", timeRange == null ? "" : timeRange,
                "basis", basis == null ? "" : basis,
                "apiCode", apiCode == null ? "" : apiCode,
                "display", display);
    }
}
