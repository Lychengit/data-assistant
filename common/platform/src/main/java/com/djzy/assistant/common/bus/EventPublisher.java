package com.djzy.assistant.common.bus;

import com.djzy.assistant.spi.AgentEvent;

/** 事件发布端口（§8.4）：Redis Stream / PG Outbox / RocketMQ 预留，实现可插拔。 */
public interface EventPublisher {

    void publish(AgentEvent event);
}
