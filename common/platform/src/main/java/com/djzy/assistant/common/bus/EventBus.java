package com.djzy.assistant.common.bus;

/**
 * 可插拔事件总线（§8.4）：{@code event.bus.type=redis-stream|pg-outbox|rocketmq}。
 *
 * <p>队列只是**落库通道**，不是唯一副本——唯一副本是本地 append-only 日志（ADR-28）。
 */
public interface EventBus extends EventPublisher {

    String type();

    void subscribe(EventConsumer consumer);
}
