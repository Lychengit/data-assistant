package com.djzy.assistant.spi;

import java.util.Optional;

/**
 * 状态持久化端口：平台提供落点（骨架期 PG，§19.5），运行时只按 key 读写中立快照。
 *
 * <p>按 {@code (userId, sessionId, key)} 三元组寻址；实现不得让运行时看到 DataSource。
 */
public interface RuntimeStatePort {

    Optional<Snapshot> load(String userId, String sessionId, String key);

    void save(String userId, String sessionId, String key, Snapshot snapshot);

    void delete(String userId, String sessionId, String key);
}
