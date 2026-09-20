-- I6 数据访问审计（§18.4.5 I6 / §20.4）：数据访问是否合规以接口服务为权威源。
-- 与 permission_audit 同样是合规审计——**直写 PG、一条不漏**，不参与「日志先行 + 异步落库」的取舍（§0.3-3）。

CREATE TABLE data_access_audit (
    id             BIGSERIAL,
    request_id     VARCHAR(64)  NOT NULL,             -- 与 agent 侧 tool_call 以 requestId 关联（§20.4）
    trace_id       VARCHAR(64),
    caller_key_id  VARCHAR(64),                       -- I1 验签得到的调用方 keyId（不含密钥）
    user_id        VARCHAR(64)  NOT NULL,
    api_code       VARCHAR(64)  NOT NULL,
    skill_code     VARCHAR(64),
    scope_snapshot JSONB,                             -- 本次使用的数据范围快照（多角色并集，§19.1）
    outcome        VARCHAR(24)  NOT NULL,             -- ALLOW / DENY / ERROR
    reason         VARCHAR(255),
    row_count      INT,
    truncated      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)                      -- 主键带时间列，为月分区留口子（§20.5）
);

CREATE INDEX ix_data_access_audit_request ON data_access_audit (request_id);
CREATE INDEX ix_data_access_audit_user_created ON data_access_audit (user_id, created_at);
