package com.djzy.assistant.agentweb.session;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例用的停止信号（内存版）。
 *
 * <p>只适用于**单副本**：停止请求与正在跑的那一轮必须落在同一个实例上才生效。
 * 多副本必须换 {@link RedisTurnStopSignalStore}（见 application.yml 的说明）。
 */
public final class InMemoryTurnStopSignalStore implements TurnStopSignalStore {

    /** 一轮最多跑几分钟，信号留 10 分钟足够；过期条目在下一次同名访问时自然失效。 */
    private static final long TTL_MS = Duration.ofMinutes(10).toMillis();

    private final Map<String, Long> requestedUntilMs = new ConcurrentHashMap<>();

    @Override
    public void request(String sessionId, String turnId) {
        requestedUntilMs.put(key(sessionId, turnId), System.currentTimeMillis() + TTL_MS);
    }

    @Override
    public boolean isRequested(String sessionId, String turnId) {
        Long until = requestedUntilMs.get(key(sessionId, turnId));
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            requestedUntilMs.remove(key(sessionId, turnId));
            return false;
        }
        return true;
    }

    @Override
    public void clear(String sessionId, String turnId) {
        requestedUntilMs.remove(key(sessionId, turnId));
    }

    private static String key(String sessionId, String turnId) {
        return sessionId + ':' + turnId;
    }
}
