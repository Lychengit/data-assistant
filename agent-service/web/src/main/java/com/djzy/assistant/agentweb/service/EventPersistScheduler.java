package com.djzy.assistant.agentweb.service;

import com.djzy.assistant.common.bus.EventPersistConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * event-persist 的驱动（§19.6）：按攒批节奏把队列里的事件搬进 PG 事实表。
 *
 * <p>用户在等答案的时候**不经过这里**——事件早就在本地 append-only 日志里落定了（唯一事实源），
 * 这个循环只负责让回放 / 监控读到的事实表跟上；「答案已经到了、事实表还差 ≤1 秒」是明示的（§19.6）。
 *
 * <p>没配队列（{@code agent-service.event-bus=none}）时这里什么都不做。
 * 启动时的「追赶投递」不在这里，见 {@code AgentServiceConfig#redeliverPendingOnStartup}。
 */
@Component
public class EventPersistScheduler {

    private final ObjectProvider<EventPersistConsumer> consumerProvider;

    public EventPersistScheduler(ObjectProvider<EventPersistConsumer> consumerProvider) {
        this.consumerProvider = consumerProvider;
    }

    @Scheduled(fixedDelayString = "${agent-service.event-persist-interval-ms:200}")
    public void drain() {
        EventPersistConsumer consumer = consumerProvider.getIfAvailable();
        if (consumer == null) {
            return;
        }
        consumer.drainOnce();
    }
}