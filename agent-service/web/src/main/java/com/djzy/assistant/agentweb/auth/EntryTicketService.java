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
 *
 * <p>发券走「**抢坑位**」而不是「写入」：写不进去（键已被占用）就换一张重来。随机串够长只是把撞号
 * 概率压小，真正保证「两张活券不可能共用一个键」的是 {@link EntryTicketStore#saveIfAbsent}——
 * 没有它，撞号会静默覆盖，而券即身份，覆盖的结果就是**串号**。
 */
public final class EntryTicketService {

    public static final int TICKET_BYTES = 32;

    /** 券号撞坑后的重试次数。撞坑 = 这个键上已经躺着别的券。 */
    private static final int ISSUE_ATTEMPTS = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntryTicketStore store;
    private final Duration ttl;

    public EntryTicketService(EntryTicketStore store, Duration ttl) {
        this.store = store;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(60) : ttl;
    }

    /**
     * 发券：返回**明文券**（只此一次出现在内存里，调用方不得写日志）。
     *
     * <p>发券不是「生成一个随机串然后写进去」，而是「**抢一个坑位**」：抢不到就换一张重来。
     * 于是「两张活券共用一个键」在结构上不可能存在，也就不会出现「拿自己的券查出别人的身份」。
     *
     * <p>连撞 {@value #ISSUE_ATTEMPTS} 次的概率可以忽略（32 字节 SecureRandom）。真到了那一步，
     * 说明随机源坏了：宁可当场发不出券（用户重试一次即可），也不能把一张可能重号的券发出去。
     */
    public IssuedTicket issue(String userId, String sessionId, String turnId) {
        Instant expiresAt = Instant.now().plus(ttl);
        EntryTicket payload = new EntryTicket(userId, sessionId, turnId, expiresAt);
        for (int attempt = 0; attempt < ISSUE_ATTEMPTS; attempt++) {
            String ticket = newTicket();
            if (store.saveIfAbsent(hash(ticket), payload, ttl.toSeconds())) {
                return new IssuedTicket(ticket, sessionId, turnId, expiresAt);
            }
        }
        throw new IllegalStateException("入场券号连续占用，疑似随机源异常（§19.4）");
    }

    /** 一枚新券的明文（32 字节 SecureRandom，URL 安全编码）。 */
    private static String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
