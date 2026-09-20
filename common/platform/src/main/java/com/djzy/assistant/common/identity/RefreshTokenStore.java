package com.djzy.assistant.common.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * 刷新令牌存储端口（§19.4：JWT ≤15 分钟 + refresh token，不签发「一次管一天」的长效令牌）。
 *
 * <p>**只存哈希**（SHA-256），不存明文——令牌等同口令，进库即等同泄露。
 * 消费必须**一次性**（条件更新 `revoked_at IS NULL`），并发重放只能有一方成功。
 */
public interface RefreshTokenStore {

    void save(String tokenHash, String userId, Instant expiresAt);

    /**
     * 一次性消费：有效则**同时作废**并返回 userId（轮换，防重放）。
     *
     * @return 有效且本次消费成功的用户 id；不存在 / 已作废 / 已过期 → empty（按 401 处理）
     */
    Optional<String> consume(String tokenHash, Instant now);

    /** 登出：作废该刷新令牌（幂等；不存在也返回正常）。 */
    void revoke(String tokenHash, Instant now);
}
