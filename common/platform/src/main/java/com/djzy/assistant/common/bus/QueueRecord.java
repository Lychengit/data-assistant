package com.djzy.assistant.common.bus;

import com.djzy.assistant.spi.AgentEvent;
import java.util.Objects;

/**
 * 队列里的一条事件（§19.6）。
 *
 * @param id       队列位点（PG Outbox 主键 / Redis Stream 消息 id），确认与死信都按它定位
 * @param event    事件本体。队列上只是**副本**——唯一事实源是本地 append-only 日志（ADR-28）
 * @param attempts 已经投递失败的次数（Redis 由 XPENDING 投递计数给出，PG 由 {@code event_outbox.attempts} 给出）
 */
public record QueueRecord(String id, AgentEvent event, int attempts) {

    public QueueRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(event, "event");
        if (attempts < 0) {
            attempts = 0;
        }
    }

    public String eventId() {
        return event.eventId();
    }
}