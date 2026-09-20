package com.djzy.assistant.spi.tool;

/**
 * 副作用等级：只决定确认 / 重试 / 审计 / 超时，**不是权限**（§4.6）。
 *
 * <p>权限由「是否授予该接口」决定；要禁止某角色写，就不给它写接口的授权。
 */
public enum SideEffect {
    READ,
    WRITE;

    public boolean isWrite() {
        return this == WRITE;
    }
}
