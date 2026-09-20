package com.djzy.assistant.common.sse;

import java.util.Map;
import java.util.Objects;

/**
 * 一条 SSE 事件（线格式：{@code event: <type>\n data: <json>\n\n}）。
 *
 * @param type 业务事件类型
 * @param payload 载荷（§12.2 规定的字段）
 */
public record SseEvent(SseEventType type, Map<String, Object> payload) {

    public SseEvent {
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static SseEvent of(SseEventType type, Map<String, Object> payload) {
        return new SseEvent(type, payload);
    }
}
