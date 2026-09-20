package com.djzy.assistant.core.tool.guard;

/** 重复调用计数端口（§10.4-2）：实现用 Redis 原子计数（跨实例同口径）。 */
public interface RepeatCallStore {

    int incrementAndGet(String userId, String turnId, String callSignature);
}
