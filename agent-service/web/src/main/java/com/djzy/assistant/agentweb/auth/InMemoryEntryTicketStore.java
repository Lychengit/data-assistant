package com.djzy.assistant.agentweb.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单实例内存实现（骨架期默认）。多副本部署必须换 {@link RedisEntryTicketStore}。
 *
 * <p>消费用 {@code remove} 保证一次性：并发的两个连接只有一个拿得到券。
 */
public final class InMemoryEntryTicketStore implements EntryTicketStore {

    private final ConcurrentMap<String, EntryTicket> tickets = new ConcurrentHashMap<>();

    /**
     * 用 {@code putIfAbsent} 占坑：键上已经有值就原样留着，绝不覆盖（券就是身份，覆盖 = 串号）。
     *
     * <p>这里不按 TTL 清理：内存实现只服务单实例骨架期，用完即弃；多副本必须换
     * {@link RedisEntryTicketStore}（它用 {@code SET NX PX}，TTL 由 Redis 自己管）。
     */
    @Override
    public boolean saveIfAbsent(String ticketHash, EntryTicket ticket, long ttlSeconds) {
        return tickets.putIfAbsent(ticketHash, ticket) == null;
    }

    @Override
    public Optional<EntryTicket> consume(String ticketHash, Instant now) {
        EntryTicket ticket = ticketHash == null ? null : tickets.remove(ticketHash);
        if (ticket == null || ticket.expired(now)) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    /** 供运维/测试观察当前未消费券数（不入业务逻辑）。 */
    public int size() {
        return tickets.size();
    }
}
