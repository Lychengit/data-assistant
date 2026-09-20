package com.djzy.assistant.common.security;

import java.time.Duration;

/**
 * nonce 一次性消费端口（§20.1.4-3）：Redis {@code SET key value NX EX 600}，键 = {@code keyId:nonce}，
 * TTL 略大于时间窗。
 *
 * <p>实现必须跨实例一致（多副本同口径，§2.3）；写入失败（已存在）即判重放。
 */
public interface NonceStore {

    /** @return true 表示本次是新消费；false 表示 nonce 已被使用（重放）。 */
    boolean tryConsume(String keyId, String nonce, Duration ttl);
}
