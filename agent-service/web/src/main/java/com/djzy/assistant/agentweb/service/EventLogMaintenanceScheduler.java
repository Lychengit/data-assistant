package com.djzy.assistant.agentweb.service;

import com.djzy.assistant.common.eventlog.AppendOnlyEventLog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地事件日志的维护驱动（§19.6 / ADR-28）：按固定间隔做「落盘 → 裁剪 → 滚动 → 清理留档」。
 *
 * <p><b>没有它会发生什么</b>：日志文件只增不减——写日志是热路径，谁都不会顺手去删；
 * 一个实例连着跑，那个 {@code agent-{instanceId}.jsonl} 就一直长下去，最后把磁盘占满。
 * 而它恰恰是审计的唯一事实源，撑爆之后连追溯都做不了。
 *
 * <p><b>为什么不需要分布式锁</b>：每个实例只维护自己那个文件（文件名里带 instanceId），
 * 两台实例不会碰同一个文件——这是一件纯粹的「本机家务活」。§2.3 说的分布式锁是给
 * 跨副本共享的资源用的（例如事实表落库），与本类无关。
 *
 * <p>间隔默认 1 分钟：维护是「扫一遍自己的文件」，比攒批落库轻；再勤也没必要，
 * 留档晚删一分钟对磁盘没有任何影响。
 *
 * <p>间隔直接用 {@code @Scheduled} 的占位符读配置（{@code agent-service.event-log-maintenance-interval-ms}），
 * 而不是从 {@code AgentServiceProperties} 取：调度间隔要在容器初始化时就解析出来，走配置类反而绕远路。
 * 这也是 {@code EventPersistScheduler} 一直在用的写法。
 */
@Component
public class EventLogMaintenanceScheduler {

    private final AppendOnlyEventLog eventLog;

    public EventLogMaintenanceScheduler(AppendOnlyEventLog eventLog) {
        this.eventLog = eventLog;
    }

    @Scheduled(fixedDelayString = "${agent-service.event-log-maintenance-interval-ms:60000}")
    public void maintain() {
        eventLog.maintenance();
    }
}
