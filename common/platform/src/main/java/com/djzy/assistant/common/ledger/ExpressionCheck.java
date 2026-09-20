package com.djzy.assistant.common.ledger;

import java.math.BigDecimal;

/**
 * 单条算式复算结论。
 *
 * @param reason 不合规原因码（PARSE_ERROR / UNKNOWN_REF / MISMATCH / UNIT_MISMATCH / DISPLAY_MISMATCH）
 */
public record ExpressionCheck(
        String exprId,
        boolean passed,
        BigDecimal computed,
        BigDecimal declared,
        BigDecimal tolerance,
        String reason) {
}
