package com.djzy.assistant.common.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单实例内存 nonce 存储（§20.1.4 脚注的退化为单实例内存 LRU）。
 *
 * <p>**必须明示的代价**：多副本部署下防重放不完整（A 实例消费的 nonce，B 实例不认识）；
 * 因此只作为 dev / 单实例骨架期兜底，生产必须换 Redis（{@code SET key value NX EX 600}）。
 */
public final class InMemoryNonceStore implements NonceStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNonceStore.class);
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final int maxEntries;

    public InMemoryNonceStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryNonceStore(int maxEntries) {
        this.maxEntries = maxEntries;
        log.warn("使用单实例内存 nonce 存储：多副本部署下防重放不完整（§20.1.4），生产请换 Redis 实现");
    }

    @Override
    public boolean tryConsume(String keyId, String nonce, Duration ttl) {
        if (keyId == null || nonce == null || nonce.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        purgeExpired(now);
        long expiresAt = now + Math.max(1L, ttl == null ? 0L : ttl.toMillis());
        String key = keyId + ":" + nonce;
        Long previous = seen.putIfAbsent(key, expiresAt);
        if (previous == null) {
            return true;
        }
        if (previous > now) {
            return false;
        }
        return seen.replace(key, previous, expiresAt);
    }

    public int size() {
        return seen.size();
    }

    private void purgeExpired(long now) {
        if (seen.size() < maxEntries) {
            return;
        }
        seen.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
