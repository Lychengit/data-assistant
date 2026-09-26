package com.djzy.assistant.common.eventlog;

import com.djzy.assistant.common.bus.EventPublisher;
import com.djzy.assistant.spi.AgentEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 落库口径的唯一实现（§19.6 / ADR-28）：**先追加写本地日志（权威副本）→ 再投递进队列**。
 *
 * <p>不变量：
 * <ul>
 *   <li>事件不得消失：Redis / 消费者故障只允许造成 PG 滞后；
 *   <li>Redis 不可用时继续写本地日志 + 计数告警，不丢弃、不静默；
 *   <li>投递确认（XACK）后才推进日志位点；重启时扫描日志把「投递未确认」的事件重新入队；
 *   <li>用户等待路径上没有落库动作——这里只有本地顺序写。
 * </ul>
 */
public final class LogFirstEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogFirstEventPublisher.class);

    private final AppendOnlyEventLog eventLog;
    private final EventPublisher bus;
    private final EventLogMetrics metrics;

    public LogFirstEventPublisher(AppendOnlyEventLog eventLog, EventPublisher bus, EventLogMetrics metrics) {
        this.eventLog = eventLog;
        this.bus = bus;
        this.metrics = metrics == null ? EventLogMetrics.noop() : metrics;
    }

    @Override
    public void publish(AgentEvent event) {
        AppendResult append = eventLog.append(event);
        try {
            bus.publish(event);
            eventLog.ack(append.endOffset());
        } catch (RuntimeException e) {
            // 队列不可用：本地日志已是权威副本，不阻塞用户；积压由观测告警，恢复后追赶投递。
            metrics.writeFailed(e);
            log.warn("事件投递失败（已写本地日志，等待追赶投递）：eventId={}", event.eventId(), e);
        }
    }

    /** 进程重启时追赶「投递未确认」事件（event_id 幂等，重放安全）。 */
    public int redeliverPending() {
        List<AgentEvent> pending = eventLog.pendingRedelivery();
        int delivered = 0;
        for (AgentEvent event : pending) {
            try {
                bus.publish(event);
                delivered++;
            } catch (RuntimeException e) {
                metrics.writeFailed(e);
                log.warn("追赶投递中断，剩余 {} 条待重投", pending.size() - delivered, e);
                break;
            }
        }
        return delivered;
    }

    /** 底下的本地日志：定时维护（落盘 / 裁剪 / 滚动 / 清理留档）由它自己做，见 {@code AppendOnlyEventLog#maintenance()}。 */
    public AppendOnlyEventLog eventLog() {
        return eventLog;
    }
}
