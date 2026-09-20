package com.djzy.assistant.common.ledger;

/** 算式不合规（白名单以外的算子 / 出现自由字面常量 / 语法错误）→ 走 §6.3 处置分级，而不是硬拦。 */
public class ExpressionParseException extends RuntimeException {

    private final String reason;

    public ExpressionParseException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
