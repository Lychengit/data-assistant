package com.djzy.assistant.common.bus;

import com.djzy.assistant.spi.AgentEvent;
import java.util.List;

/**
 * PG 事实表写入端口（§19.6 / §8.3）：**一批一个事务**，按 {@code event_id} 唯一键
 * {@code ON CONFLICT DO NOTHING} 幂等——at-least-once 下不重不漏。
 *
 * <p>事实表只是**查询视图**（回放 / 评估 / 监控都读它），唯一事实源仍是本地 append-only 日志。
 * 所以这里允许「同一批里配不上就留空」，但绝不允许写错：写不进去就抛错，由消费者重试。
 */
public interface AgentEventFactWriter {

    /** @return 本批**实际新增**的事实行数（重复投递时为 0） */
    int writeBatch(List<AgentEvent> events);
}