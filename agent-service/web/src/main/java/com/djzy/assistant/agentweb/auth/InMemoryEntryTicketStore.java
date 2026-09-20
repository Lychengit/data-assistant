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

    @Override
    public void save(String ticketHash, EntryTicket ticket, long ttlSeconds) {
        tickets.put(ticketHash, ticket);
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
