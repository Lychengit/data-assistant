-- V9：运行时状态落 PG（§19.5 / ADR-32）
--
-- 一次落地解决四件事：HITL 挂起恢复 / 服务重启恢复 / 换 pod 恢复 / 断线续传（§19.5）。
-- 骨架期这张表是本地卷上的 JSON 快照（FileRuntimeStatePort）；换成 PG 之后，
-- SSE 连接可以落到任意副本，挂起状态不再跟着进程走（§2.3 隐性单点）。
--
-- 寻址：(user_id, session_id, state_key) 三元组。§19.5 的二元组是最常用形态
-- （key = 'turn'），额外一维留给同一会话的多段状态，不必为此再加表。
--
-- runtime_id / runtime_version 必须存：§19.14 不允许跨运行时恢复同一会话，
-- 运行时在 resume 时比对不一致就抛 RuntimeMismatchException（拒绝，而不是尽力而为）。
--
-- 写入方：agent-service（运行时通过 RuntimeStatePort 读写，运行时看不到 DataSource）。
-- 注意：这是**可变状态**（挂起快照会被覆盖），所以主键是寻址三元组而不是 created_at；
-- §20.5 的「事实表主键带 created_at」约束针对的是审计类事实表（agent_step / tool_call 等）。

CREATE TABLE agent_state (
    user_id             VARCHAR(64)  NOT NULL,
    session_id          VARCHAR(64)  NOT NULL,
    state_key           VARCHAR(128) NOT NULL,
    runtime_id          VARCHAR(64)  NOT NULL,           -- 哪个运行时写的（§19.14 跨实现恢复要拒绝）
    runtime_version     VARCHAR(64)  NOT NULL,
    snapshot_created_at BIGINT       NOT NULL,           -- 快照生成时刻（epoch ms，运行时给的）
    payload             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, session_id, state_key)
);

-- 按会话清理 / 巡检（「这个会话还有几段挂起状态」）走这条索引，不必扫全表。
CREATE INDEX ix_agent_state_session ON agent_state (session_id, updated_at DESC);

COMMENT ON TABLE agent_state IS '运行时挂起/快照状态（§19.5）：(user_id, session_id, state_key) → 中立 Snapshot';