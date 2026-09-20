package com.djzy.assistant.common.bus;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 事件在队列上的载荷编解码（§19.6）。
 *
 * <p>队列里存**事件本体**而不是指针：这样重启后的追赶投递、以及换队列实现（Redis Stream / RocketMQ）
 * 都不依赖任何进程内状态——队列只负责搬运，不负责解释。
 */
public final class AgentEventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private AgentEventCodec() {}

    public static String encode(AgentEvent event) {
        try {
            return MAPPER.writeValueAsString(event.toMap());
        } catch (Exception e) {
            throw new IllegalStateException("事件序列化失败：eventId=" + event.eventId(), e);
        }
    }

    public static AgentEvent decode(String json) {
        try {
            Map<String, Object> m = MAPPER.readValue(json, MAP_TYPE);
            return new AgentEvent(
                    (String) m.get("eventId"),
                    AgentEventType.valueOf((String) m.get("type")),
                    (String) m.get("sessionId"),
                    (String) m.get("turnId"),
                    (String) m.get("traceId"),
                    longOf(m.get("seq")),
                    longOf(m.get("timestamp")),
                    (String) m.get("source"),
                    asPayload(m.get("payload")));
        } catch (Exception e) {
            throw new IllegalStateException("队列载荷不是合法事件（不静默丢弃，交人工看）：" + json, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayload(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}