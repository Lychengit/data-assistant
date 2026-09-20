-- 骨架期初始化（§4.2 / §4.7 / §8.3 / §19.14 / §20.7 / ADR-28）
-- 迁移工具：Flyway（§20.8），禁止生产手工改表。所有时间列 timestamptz（UTC 存储，业务时区 Asia/Shanghai）。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============ 用户与角色（§4.2）============
CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    dept_id       BIGINT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',   -- active / disabled：停用立即失效（§19.4）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sys_role (
    id         BIGSERIAL PRIMARY KEY,
    role_code  VARCHAR(64) NOT NULL UNIQUE,
    role_name  VARCHAR(64) NOT NULL,
    remark     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    role_id BIGINT NOT NULL REFERENCES sys_role(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_dept (
    id        BIGSERIAL PRIMARY KEY,
    parent_id BIGINT,
    name      VARCHAR(64) NOT NULL,
    path      VARCHAR(255),
    sort      INT NOT NULL DEFAULT 0
);

-- ============ 技能 / 接口授权（§4.7）============
CREATE TABLE sys_skill (
    id           BIGSERIAL PRIMARY KEY,
    skill_code   VARCHAR(64) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    owner_type   VARCHAR(16) NOT NULL DEFAULT 'platform',  -- platform / dept / personal
    owner_id     BIGINT,
    read_only    BOOLEAN NOT NULL DEFAULT TRUE,
    param_schema JSONB,
    status       VARCHAR(16) NOT NULL DEFAULT 'draft',     -- draft / active / disabled
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sys_api (
    id               BIGSERIAL PRIMARY KEY,
    api_code         VARCHAR(64) NOT NULL UNIQUE,
    name             VARCHAR(128) NOT NULL,
    service          VARCHAR(64) NOT NULL,                 -- 服务名（固定配置发现，§20.3）
    method           VARCHAR(8)  NOT NULL DEFAULT 'read',  -- 副作用等级，不是权限（§4.6）
    resource         VARCHAR(128),
    param_schema     JSONB,
    column_whitelist JSONB,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    owner_id         BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role_skill (
    role_id  BIGINT NOT NULL REFERENCES sys_role(id),
    skill_id BIGINT NOT NULL REFERENCES sys_skill(id),
    can_view BOOLEAN NOT NULL DEFAULT TRUE,               -- 可见即可执行（§4.7）
    PRIMARY KEY (role_id, skill_id)
);

CREATE TABLE skill_api (
    skill_id    BIGINT NOT NULL REFERENCES sys_skill(id),
    api_id      BIGINT NOT NULL REFERENCES sys_api(id),
    approved    BOOLEAN NOT NULL DEFAULT FALSE,           -- 发布审核一次（ADR-19）
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    PRIMARY KEY (skill_id, api_id)
);

CREATE TABLE role_api (
    role_id BIGINT NOT NULL REFERENCES sys_role(id),
    api_id  BIGINT NOT NULL REFERENCES sys_api(id),
    scope   JSONB NOT NULL,                               -- 数据范围唯一来源；多角色取并集（§19.1）
    PRIMARY KEY (role_id, api_id)
);

-- ============ 合规审计：直写 PG，一条不漏（§0.3-3 / §20.4 / §20.7）============
CREATE TABLE permission_audit (
    id             BIGSERIAL,
    trace_id       VARCHAR(64) NOT NULL,
    user_id        VARCHAR(64),
    role_ids       JSONB,
    tool_name      VARCHAR(128),
    decision       VARCHAR(16) NOT NULL,                  -- ALLOW / DENY
    reason         VARCHAR(255),
    scope_snapshot JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);

CREATE TABLE config_audit (
    id         BIGSERIAL,
    who        VARCHAR(64) NOT NULL,
    target     VARCHAR(64) NOT NULL,                      -- 角色 / 技能 / 接口 / 字典
    field      VARCHAR(128) NOT NULL,
    before     JSONB,
    after      JSONB,
    request_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);

-- ============ 会话事实表（§8.3 / §19.14）============
CREATE TABLE conversation_session (
    id              BIGSERIAL,
    session_id      VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    title           VARCHAR(255),
    runtime_id      VARCHAR(64) NOT NULL,                 -- 会话绑定运行时（§19.14）
    runtime_version VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);
CREATE UNIQUE INDEX ux_conversation_session_session_id ON conversation_session (session_id);

CREATE TABLE conversation_turn (
    id               BIGSERIAL,
    event_id         VARCHAR(64) NOT NULL,                -- 幂等键：ON CONFLICT DO NOTHING（§8.3）
    session_id       VARCHAR(64) NOT NULL,
    turn_id          VARCHAR(64) NOT NULL,
    trace_id         VARCHAR(64) NOT NULL,
    user_input       TEXT,
    context_snapshot JSONB,
    final_answer     TEXT,
    figures_json     JSONB,
    expressions_json JSONB,
    ledger_json      JSONB,
    verify_result    JSONB,
    latency_ms       BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);
CREATE UNIQUE INDEX ux_conversation_turn_event_id ON conversation_turn (event_id);
CREATE INDEX ix_conversation_turn_trace ON conversation_turn (trace_id);

CREATE TABLE agent_step (
    id          BIGSERIAL,
    event_id    VARCHAR(64) NOT NULL,
    session_id  VARCHAR(64) NOT NULL,
    turn_id     VARCHAR(64) NOT NULL,
    trace_id    VARCHAR(64) NOT NULL,
    seq         BIGINT NOT NULL,
    type        VARCHAR(16) NOT NULL,                     -- thought / action / observation
    content     TEXT,
    tool_name   VARCHAR(128),
    tool_args   JSONB,
    tool_result JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);
CREATE UNIQUE INDEX ux_agent_step_event_id ON agent_step (event_id);

CREATE TABLE llm_call (
    id             BIGSERIAL,
    event_id       VARCHAR(64) NOT NULL,
    trace_id       VARCHAR(64) NOT NULL,
    turn_id        VARCHAR(64) NOT NULL,
    model          VARCHAR(64),
    prompt_hash    VARCHAR(64),
    prompt_preview TEXT,
    response_preview TEXT,
    tokens_in      INT,
    tokens_out     INT,
    cost           NUMERIC(18, 6),
    latency_ms     BIGINT,
    status         VARCHAR(16),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);
CREATE UNIQUE INDEX ux_llm_call_event_id ON llm_call (event_id);

CREATE TABLE tool_call (
    id          BIGSERIAL,
    event_id    VARCHAR(64) NOT NULL,
    trace_id    VARCHAR(64) NOT NULL,
    turn_id     VARCHAR(64) NOT NULL,
    tool_name   VARCHAR(128) NOT NULL,
    args        JSONB,
    result_size BIGINT,
    status      VARCHAR(16),
    error       TEXT,
    latency_ms  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);
CREATE UNIQUE INDEX ux_tool_call_event_id ON tool_call (event_id);

CREATE TABLE user_feedback (
    id         BIGSERIAL,
    turn_id    VARCHAR(64) NOT NULL,
    trace_id   VARCHAR(64) NOT NULL,
    rating     SMALLINT NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);

CREATE TABLE event_dead_letter (
    id         BIGSERIAL PRIMARY KEY,
    event_id   VARCHAR(64) NOT NULL,
    payload    JSONB NOT NULL,
    error      TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 口径字典（§6.1 / §6.3）============
CREATE TABLE metric_dictionary (
    id            BIGSERIAL PRIMARY KEY,
    metric_key    VARCHAR(64) NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    unit          VARCHAR(32),
    rounding      INT NOT NULL DEFAULT 1,                 -- 展示精度 → 复算容差 = 最后一位的半个单位
    derived_of    JSONB,                                  -- 高频派生（同比 / 环比 / 占比）由接口直接算好
    unit_convert  JSONB,
    basis         TEXT,                                   -- 口径说明（溯源标注用）
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 待确认项与技能执行审计（§19.9 / §19.10 / §19.2）============
CREATE TABLE pending_confirm (
    confirm_id  VARCHAR(64) PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    turn_id     VARCHAR(64),
    user_id     VARCHAR(64) NOT NULL,
    action      VARCHAR(128),
    summary     TEXT,
    status      VARCHAR(16) NOT NULL DEFAULT 'pending',   -- pending / approved / rejected / expired
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE skill_exec_audit (
    id             BIGSERIAL PRIMARY KEY,
    skill_code     VARCHAR(64) NOT NULL,
    package_hash   VARCHAR(80) NOT NULL,                  -- 内容 SHA-256（§19.2）
    script_hash    VARCHAR(80),
    request_id     VARCHAR(64) NOT NULL,
    user_id        VARCHAR(64) NOT NULL,
    status         VARCHAR(24) NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ
);

CREATE TABLE artifact (
    artifact_id VARCHAR(64) PRIMARY KEY,
    session_id  VARCHAR(64),
    turn_id     VARCHAR(64),
    name        VARCHAR(255),
    size_bytes  BIGINT,
    locator     TEXT NOT NULL,
    expires_at  TIMESTAMPTZ,                              -- 默认 7 天（§19.10）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 演示种子（骨架期最小集）============
INSERT INTO sys_role (role_code, role_name, remark) VALUES
    ('admin', '系统管理员', '全部权限，含管理配置'),
    ('boss', '上级领导', '跨域可见，范围可配置'),
    ('hr', '人事', '人事域'),
    ('assistant', '助理', '辅助查询'),
    ('aitc', '信息中心', '信息中心域'),
    ('pharmacy', '药事', '药事域'),
    ('finance', '财务', '财务域'),
    ('market', '市场部', '市场域')
ON CONFLICT (role_code) DO NOTHING;
