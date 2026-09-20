package com.djzy.assistant.common.confirm;

/** 待确认项状态（{@code pending_confirm.status}，§19.9 / §19.10）。 */
public enum ConfirmStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}
