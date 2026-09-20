package com.djzy.assistant.common.ledger;

/**
 * 数字一致性校验结论（§6.3 处置分级）。
 *
 * <p>不硬拦、不静默改数：不合规 → 让模型补一次；补不上 → 降级为「只展示接口原始数据 + 口径标注」，
 * 计一次 {@code hallucination_blocked}（跑偏信号 §10.4）。
 */
public enum VerificationOutcome {
    /** 复算通过，放行。 */
    PASS,
    /** 纯定性回答（无数字）→ 放行，计入「无数字断言占比」。 */
    NO_DIGITS,
    /** 不合规 → 降级展示，计一次 hallucination_blocked。 */
    DEGRADED
}
