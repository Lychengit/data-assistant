package com.djzy.assistant.agentweb.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * SSE 一次性入场券的载荷（§19.4）：它只回答「谁、要接哪一轮的流」。
 *
 * <p>**不装令牌**——券本身就是短时一次性的凭据，令牌留在 Authorization 头里（不进 URL / 日志）。
 *
 * @param userId 已验明的身份（会话归属校验用）
 * @param sessionId 目标会话
 * @param turnId 目标轮次；**空串 = 不指定轮次**（换券续看时，如果哪台实例都没在跑这一轮，
 *     就没有轮次可绑。空串而不是 {@code null}，是因为券要序列化进 Redis，少一种「空值」的写法）
 * @param expiresAt 过期时刻
 */
public record EntryTicket(String userId, String sessionId, String turnId, Instant expiresAt) {

    public EntryTicket {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        // 不指定轮次是正常情况（见 turnId 的说明），所以这里把 null 归一成空串，不算参数错误
        turnId = turnId == null ? "" : turnId;
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
