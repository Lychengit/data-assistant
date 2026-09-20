package com.djzy.assistant.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 中立事件（§8.4）：框架事件与业务事件统一用这个模型承载。
 *
 * <p>必填字段（TCK-9）：{@code eventId} / {@code type} / {@code turnId} / {@code traceId} / {@code seq}。
 */
public record AgentEvent(
        String eventId,
        AgentEventType type,
        String sessionId,
        String turnId,
        String traceId,
        long seq,
        long timestampEpochMs,
        String source,
        Map<String, Object> payload) {

    public AgentEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(traceId, "traceId");
        source = source == null ? "main" : source;
        payload = immutablePayload(payload);
    }

    /** 载荷中不允许 null 值（Map.copyOf 会拒绝）；null 值在此被过滤，避免事件构造期崩溃。 */
    static Map<String, Object> immutablePayload(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (key != null && value != null) {
                cleaned.put(key, value);
            }
        });
        return Map.copyOf(cleaned);
    }

    public static AgentEvent of(
            AgentEventType type,
            String sessionId,
            String turnId,
            String traceId,
            long seq,
            Map<String, Object> payload) {
        return new AgentEvent(
                UUID.randomUUID().toString(),
                type,
                sessionId,
                turnId,
                traceId,
                seq,
                System.currentTimeMillis(),
                "main",
                payload);
    }

    public static AgentEvent of(
            AgentEventType type,
            String sessionId,
            String turnId,
            String traceId,
            long seq,
            String source,
            Map<String, Object> payload) {
        return new AgentEvent(
                UUID.randomUUID().toString(),
                type,
                sessionId,
                turnId,
                traceId,
                seq,
                System.currentTimeMillis(),
                source,
                payload);
    }

    public static Builder builder(AgentEventType type) {
        return new Builder(type);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("type", type.name());
        m.put("sessionId", sessionId);
        m.put("turnId", turnId);
        m.put("traceId", traceId);
        m.put("seq", seq);
        m.put("timestamp", timestampEpochMs);
        m.put("source", source);
        m.put("payload", payload);
        return m;
    }

    public static final class Builder {
        private final AgentEventType type;
        private String eventId = UUID.randomUUID().toString();
        private String sessionId;
        private String turnId;
        private String traceId;
        private long seq;
        private long timestampEpochMs = System.currentTimeMillis();
        private String source = "main";
        private final Map<String, Object> payload = new LinkedHashMap<>();

        private Builder(AgentEventType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder eventId(String v) {
            this.eventId = v;
            return this;
        }

        public Builder session(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder turn(String v) {
            this.turnId = v;
            return this;
        }

        public Builder trace(String v) {
            this.traceId = v;
            return this;
        }

        public Builder seq(long v) {
            this.seq = v;
            return this;
        }

        public Builder timestamp(long v) {
            this.timestampEpochMs = v;
            return this;
        }

        public Builder source(String v) {
            this.source = v;
            return this;
        }

        public Builder put(String key, Object value) {
            this.payload.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, ?> values) {
            if (values != null) {
                values.forEach(this.payload::put);
            }
            return this;
        }

        public AgentEvent build() {
            return new AgentEvent(
                    eventId, type, sessionId, turnId, traceId, seq, timestampEpochMs, source, payload);
        }
    }
}
