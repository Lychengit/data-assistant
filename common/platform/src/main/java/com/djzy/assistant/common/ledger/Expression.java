package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模型自己算出来的数（§6.3）：{@code {exprId, text, refs[], result, unit, display}}。
 *
 * <p>{@code text} 只能出现台账编号与白名单算子，不能出现字面数字常量
 * （百分比换算的 100 这类常量来自字典定义，不算自由常量）。
 *
 * @param display 展示格式化（如 {@code -11.7%}）；校验通过后与计算过程一起展示给用户
 */
public record Expression(
        String exprId, String text, List<String> refs, BigDecimal result, String unit, String display) {

    public Expression {
        Objects.requireNonNull(exprId, "exprId");
        Objects.requireNonNull(text, "text");
        refs = refs == null ? List.of() : List.copyOf(refs);
        unit = unit == null ? "" : unit;
    }

    public static Expression parse(Map<String, Object> raw) {
        Object resultValue = raw.get("result");
        BigDecimal result =
                resultValue == null ? null : new BigDecimal(String.valueOf(resultValue));
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) raw.getOrDefault("refs", List.of());
        return new Expression(
                String.valueOf(raw.getOrDefault("exprId", "")),
                String.valueOf(raw.getOrDefault("text", "")),
                refs,
                result,
                raw.get("unit") == null ? "" : String.valueOf(raw.get("unit")),
                raw.get("display") == null ? null : String.valueOf(raw.get("display")));
    }
}
