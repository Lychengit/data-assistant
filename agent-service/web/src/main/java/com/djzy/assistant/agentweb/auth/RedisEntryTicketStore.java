package com.djzy.assistant.agentweb.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 多副本实现（§19.4）：{@code GET} + {@code DEL} 消费，TTL 到期自动清理。
 *
 * <p>不用 {@code GETDEL}：它是 Redis 6.2+ 的命令，而现场可能跑着更老的版本（本机是 3.0）。
 * 「先 GET、再 DEL，只有删除成功的那个才算赢」在任何版本上都是**恰好一次**：
 * 并发抢同一张券时，两个请求都读到了值，但只有一个删到，另一个拿到 0 条删除记录。
 *
 * <p>Redis 不可用时**抛 {@link EntryTicketStore.UnavailableException}（fail-closed）**：
 * 拿不到「券没被用过」的结论就不得建立连接。
 */
public final class RedisEntryTicketStore implements EntryTicketStore {

    static final String KEY_PREFIX = "entryticket:";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate template;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisEntryTicketStore(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public void save(String ticketHash, EntryTicket ticket, long ttlSeconds) {
        try {
            template.opsForValue().set(KEY_PREFIX + ticketHash, serialize(ticket), java.time.Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            throw new UnavailableException("入场券存储不可用，拒绝发券（§19.4）", e);
        }
    }

    @Override
    public Optional<EntryTicket> consume(String ticketHash, Instant now) {
        if (ticketHash == null || ticketHash.isBlank()) {
            return Optional.empty();
        }
        String raw;
        boolean removed;
        try {
            String key = KEY_PREFIX + ticketHash;
            raw = template.opsForValue().get(key);
            removed = raw != null && Boolean.TRUE.equals(template.delete(key));
        } catch (RuntimeException e) {
            throw new UnavailableException("入场券存储不可用，拒绝建立连接（§19.4 fail-closed）", e);
        }
        if (raw == null || !removed) {
            return Optional.empty();
        }
        EntryTicket ticket = deserialize(raw);
        return ticket == null || ticket.expired(now) ? Optional.empty() : Optional.of(ticket);
    }

    private String serialize(EntryTicket ticket) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "userId", ticket.userId(),
                    "sessionId", ticket.sessionId(),
                    "turnId", ticket.turnId(),
                    "expiresAt", ticket.expiresAt().toString()));
        } catch (Exception e) {
            throw new IllegalStateException("入场券序列化失败", e);
        }
    }

    private EntryTicket deserialize(String raw) {
        try {
            Map<String, Object> map = mapper.readValue(raw, MAP_TYPE);
            return new EntryTicket(
                    String.valueOf(map.get("userId")),
                    String.valueOf(map.get("sessionId")),
                    String.valueOf(map.get("turnId")),
                    Instant.parse(String.valueOf(map.get("expiresAt"))));
        } catch (Exception e) {
            return null;
        }
    }
}
