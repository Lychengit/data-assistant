package com.djzy.assistant.agentweb.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次性入场券存储（§19.4）：**一次性消费**，消费即失效。
 *
 * <p>键是券的 SHA-256（库里不存明文券，和刷新令牌同款理由）；实现不得返回「消费了但没删」的状态。
 * 多副本必须换 Redis 实现——单实例内存实现无法保证跨实例一次性。
 */
public interface EntryTicketStore {

    void save(String ticketHash, EntryTicket ticket, long ttlSeconds);

    /** 原子消费：返回记录并判定其是否过期，第二遍调用必然拿不到。 */
    Optional<EntryTicket> consume(String ticketHash, Instant now);

    /** 存储不可用（如 Redis 掉线）→ fail-closed，不得放行（§20.1.4）。 */
    final class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public UnavailableException(String message) {
            super(message);
        }
    }
}
