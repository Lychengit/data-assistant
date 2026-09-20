package com.djzy.assistant.core.tool.guard;

/**
 * 写操作计数端口（§19.9）：一轮对话内写操作默认 ≤3 次。
 *
 * <p>实现必须是 Redis 原子计数（跨实例同口径，§2.3 / §9.3），**禁止本地内存计数**；
 * 本地实现仅供单测使用。
 */
public interface WriteQuotaStore {

    /** @return 本轮的累计写次数（包含本次） */
    int incrementAndGet(String userId, String turnId);
}
