package com.djzy.assistant.common.eventlog;

import com.djzy.assistant.common.bus.EventPublisher;
import com.djzy.assistant.spi.AgentEvent;

/**
 * 只落本地日志的发布器（§19.6）：没有配队列时的形态。
 *
 * <p>不是「降级」——本地 append-only 日志本来就是唯一事实源，队列只是把它搬到 PG 事实表的通道。
 * 没配队列时事件照样一条不漏地落盘，只是回放要读日志、读不到事实表。
 *
 * <p>append 成功即确认：没有下游要等，日志位点可以直接推进，保留窗口裁剪才能生效。
 */
public final class LogOnlyEventPublisher implements EventPublisher {

    private final AppendOnlyEventLog eventLog;

    public LogOnlyEventPublisher(AppendOnlyEventLog eventLog) {
        this.eventLog = eventLog;
    }

    @Override
    public void publish(AgentEvent event) {
        AppendResult append = eventLog.append(event);
        eventLog.ack(append.endOffset());
    }
}