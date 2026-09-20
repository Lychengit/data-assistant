package com.djzy.assistant.common.api;

/**
 * 接口副作用等级（§4.6）：只决定确认 / 重试 / 审计 / 超时，**不是权限**。
 *
 * <p>要禁止某角色写，就不给它写接口的授权；等级本身不构成拦截。
 */
public enum ApiKind {
    READ,
    WRITE;

    public boolean isWrite() {
        return this == WRITE;
    }
}
