package com.djzy.assistant.agentweb.auth;

import com.djzy.assistant.agentweb.web.ApiException;
import com.djzy.assistant.common.security.Hmac;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 一次性入场券发放与核销（§19.4）。
 *
 * <p>券 = 32 字节随机串；库里只存 **SHA-256**，同刷新令牌的理由。核销走「原子取走再判过期」，
 * 因此**刷新页面重连必须重新换券**，而不是把旧券留着复用——这正是「一次性」的含义。
 */
public final class EntryTicketService {

    public static final int TICKET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntryTicketStore store;
    private final Duration ttl;

    public EntryTicketService(EntryTicketStore store, Duration ttl) {
        this.store = store;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(60) : ttl;
    }

    /** 发券：返回**明文券**（只此一次出现在内存里，调用方不得写日志）。 */
    public IssuedTicket issue(String userId, String sessionId, String turnId) {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(ttl);
        store.save(hash(ticket), new EntryTicket(userId, sessionId, turnId, expiresAt), ttl.toSeconds());
        return new IssuedTicket(ticket, sessionId, turnId, expiresAt);
    }

    /** 核销：拿不到或已过期一律 401（不区分原因，防探测）。 */
    public EntryTicket consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw ApiException.unauthorized();
        }
        Optional<EntryTicket> consumed;
        try {
            consumed = store.consume(hash(ticket), Instant.now());
        } catch (EntryTicketStore.UnavailableException e) {
            throw ApiException.unavailable();
        }
        return consumed.orElseThrow(ApiException::unauthorized);
    }

    private static String hash(String ticket) {
        return Hmac.sha256Hex(ticket.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回给客户端的券信息（**不含**会话令牌）。 */
    public record IssuedTicket(String ticket, String sessionId, String turnId, Instant expiresAt) {}
}
