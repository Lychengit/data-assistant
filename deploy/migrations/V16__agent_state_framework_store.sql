-- 说明（编号为什么没有 V15）：本项目未上线，上一版会话状态脚本（曾短暂存在的 V15，建的是平台自研口径的 agent_state 表）
-- 已被本脚本整体取代并删除，免得留下「先建一张表、下一条迁移再把它删掉」这种没有意义的中间态。
-- 已经跑过旧 V15 的库什么都不用做：本脚本是自愈的（DROP IF EXISTS + CREATE IF NOT EXISTS），跑一遍就会收敛到最终形态。
-- 最终形态只有一张会话状态表：agentscope_sessions；平台侧**三个键**（platform_turn / platform_session / platform_turn_live）
-- 与框架的会话正文键（agent_state）同表不同键。

--
-- 为什么换：我们原先自己写了一个 JdbcAgentStateStore（实现框架的 AgentStateStore 契约）。
-- 后来发现框架扩展模块里本来就有一个**同样正确**的实现（agentscope-extensions-jdbc 的
-- JdbcAgentStateStore + PostgresDialect）：版本 CAS 是事务内单条 `UPDATE ... WHERE version = ?`
-- （按影响行数判断成功与否），插入是 `ON CONFLICT DO NOTHING` —— 并发写不会互相覆盖。
-- 自己再维护一份属于重复造轮子（见 doc/refactor/TASKS.md 的 T1-13），所以删掉自研实现，改用框架的。
--
-- 表名由框架方言决定：前缀 agentscope_ + 基础名 sessions → agentscope_sessions。
-- 注意 session_id 这一列装的是**槽位号**（`<userId>:<sessionId>`），不是单纯的会话号：
-- 一个会话的多段状态（agent_state / platform_turn / platform_session / platform_turn_live）
-- 是同槽位、不同 state_key；框架按用户列会话列表时用 `session_id LIKE '<userId>:%'`。
--
-- 为什么仍然写在迁移里，而不是让框架启动时自己建表：DDL 只有一处才不会有第二个真相；
-- 而且多实例同时启动时，运行期 `CREATE TABLE IF NOT EXISTS` 在 PG 上会撞（重复建表的竞态）。
-- 应用侧用的是**不自动建表**的构造器：表不存在就直接启动失败——这是我们要的失败方式。
--
-- 项目未上线：直接 DROP 旧表重建，不写数据搬迁（旧表数据没有保留价值）。上线后再改这张表就必须写真正的迁移。

DROP TABLE IF EXISTS agent_state;

CREATE TABLE IF NOT EXISTS agentscope_sessions (
    session_id  VARCHAR(255) NOT NULL,   -- 槽位号：<userId>:<sessionId>
    state_key   VARCHAR(255) NOT NULL,   -- 同一槽位下的多段状态（框架的 agent_state + 平台侧三个键）
    item_index  INT          NOT NULL DEFAULT 0,
    state_data  TEXT         NOT NULL,   -- 那坨 JSON 本身
    version     BIGINT       NOT NULL DEFAULT 0,  -- 乐观并发版本：CAS 写成功 +1
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);

-- 按槽位清理 / 巡检走这条索引（框架自己的建表语句里也有这一条，保持一致）。
CREATE INDEX IF NOT EXISTS agentscope_sessions_session_idx ON agentscope_sessions (session_id);

COMMENT ON TABLE agentscope_sessions IS '会话状态（§19.5，框架 AgentStateStore 契约）：(槽位号, state_key) → JSON 状态文档';
