package com.djzy.assistant.agentweb.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次性入场券存储（§19.4）：**一次性消费**，消费即失效。
 *
 * <p>键是券的 SHA-256（库里不存明文券，和刷新令牌同款理由）；实现不得返回「消费了但没删」的状态。
 * 多副本必须换 Redis 实现——单实例内存实现无法保证跨实例一次性。
 *
 * <p>**写入必须是「占坑」而不是「覆盖」**：这张券就是身份本身（流接口 {@code @AnonymousAccess}，
 * 身份全从券里取），所以「两张活券共用一个键」不是概率问题而是正确性问题——覆盖不会报错、不会留痕，
 * 只会让先发那张的持有者读到**别人的身份**。随机串够长只是把概率压小，兜住唯一性的是
 * {@link #saveIfAbsent} 的返回值。
 */
public interface EntryTicketStore {

    /**
     * 原子占坑：**只在键还空着的时候**写入。
     *
     * <p>拿到 {@code false} 的调用方必须换一张券重试，不许改写已有记录。
     *
     * @return {@code true} = 坑位归你（券已生效）；{@code false} = 键已被占用
     * @throws UnavailableException 存储不可用 → fail-closed，不得放行（§20.1.4）
     */
    boolean saveIfAbsent(String ticketHash, EntryTicket ticket, long ttlSeconds);

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
