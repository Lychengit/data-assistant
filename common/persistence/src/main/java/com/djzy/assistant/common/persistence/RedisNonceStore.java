package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.security.NonceStore;
import java.time.Duration;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link NonceStore} 的 Redis 实现（§20.1.4-3）：{@code SET key value NX EX 600}，
 * 键 = {@code keyId:nonce}，TTL 略大于时间窗；跨实例一致，是多副本部署的默认选择。
 *
 * <p>Redis 不可用时**抛错（fail-closed）**：拿不到「nonce 未被消费」的结论就不得放行。
 */
public final class RedisNonceStore implements NonceStore {

    static final String KEY_PREFIX = "svcnonce:";

    private final StringRedisTemplate template;

    public RedisNonceStore(StringRedisTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    @Override
    public boolean tryConsume(String keyId, String nonce, Duration ttl) {
        if (keyId == null || keyId.isBlank() || nonce == null || nonce.isBlank()) {
            return false;
        }
        Duration effective = (ttl == null || ttl.isZero() || ttl.isNegative()) ? Duration.ofSeconds(600) : ttl;
        Boolean consumed = template.opsForValue().setIfAbsent(KEY_PREFIX + keyId + ":" + nonce, "1", effective);
        return Boolean.TRUE.equals(consumed);
    }
}
