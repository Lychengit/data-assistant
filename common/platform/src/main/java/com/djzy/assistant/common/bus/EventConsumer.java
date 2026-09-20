package com.djzy.assistant.common.bus;

import com.djzy.assistant.spi.AgentEvent;
import java.util.List;

/**
 * 事件消费端口（§8.4 / §19.6）：消费端**必须幂等**（{@code event_id} 唯一键
 * {@code ON CONFLICT DO NOTHING}），at-least-once 下不重不漏。
 */
public interface EventConsumer {

    void onEvents(List<AgentEvent> batch);
}
