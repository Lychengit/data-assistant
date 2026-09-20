-- V8：事件投递通道（§19.6 / ADR-10 / ADR-28）
--
-- 队列只是**落库通道**，不是唯一副本：唯一副本是 agent-service 的本地 append-only 日志
-- （/data/eventlog/agent-{instanceId}.jsonl）。所以这张表允许被裁剪、允许重放，
-- 只要求「同一 event_id 不产生第二行」——at-least-once 下由唯一键吸收重复。
--
-- 写入方：agent-service（LogFirstEventPublisher 在写完本地日志后入队）。
-- 读取方：event-persist 消费者（攒批 → PG 事实表 → 确认）。

CREATE TABLE event_outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(64) NOT NULL,                -- 幂等键：重放不会产生第二行
    session_id      VARCHAR(64),
    turn_id         VARCHAR(64),
    payload         JSONB NOT NULL,                      -- 事件本体：换队列实现不依赖进程内状态
    attempts        INT NOT NULL DEFAULT 0,              -- 投递失败次数，达上限进 event_dead_letter
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 退避：失败后按 2^n 秒（上限 30s）推迟
    last_error      VARCHAR(500),
    delivered_at    TIMESTAMPTZ,                         -- 非空 = 已确认落库，可被保留窗口裁掉
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_event_outbox_event_id UNIQUE (event_id)
);

-- 消费者只扫「未确认」那一小段：部分索引让 pending 查询与队列大小解耦。
CREATE INDEX ix_event_outbox_pending ON event_outbox (id) WHERE delivered_at IS NULL;

-- 死信表在 V1 已建（event_dead_letter）；这里只补一条说明：
-- 死信是「超过重试上限仍写不进事实表」的事件，必须告警（§10.2），人工处理后再决定是否重放。
COMMENT ON TABLE event_dead_letter IS 'event-persist 超过重试上限的事件（§19.6）；>0 即告警，不无限重投';