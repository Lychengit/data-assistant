-- M3 技能包管理（§18.4.6 M3 / §18.5.2 / §19.2）：上传 → 自动检查 → 人工评审 → 发布成**不可变版本**。
--
-- 三条口径落在表结构上：
--   ① 内容寻址：包按内容 SHA-256 存对象，content_sha256 唯一 → 内容相同即同一对象，不存在「新包覆盖旧包」；
--   ② 只用最新版：sys_skill_version 保留历史行（可追溯 / 可复现），但**发布只对最新一封生效**；
--   ③ 审核对象是最新包：绑定接口的 approved 状态由发布动作在**同一事务**里写 skill_api。

CREATE TABLE sys_skill_version (
    id              BIGSERIAL PRIMARY KEY,
    skill_code      VARCHAR(64)  NOT NULL,
    version         VARCHAR(32)  NOT NULL,
    content_sha256  CHAR(64)     NOT NULL UNIQUE,
    storage_key     VARCHAR(255) NOT NULL,
    manifest        JSONB        NOT NULL,
    manifest_sha256 CHAR(64)     NOT NULL,
    kind            VARCHAR(16)  NOT NULL,                    -- script / agentic
    status          VARCHAR(16)  NOT NULL DEFAULT 'uploaded', -- uploaded / checked / rejected / published
    submitted_by    VARCHAR(64)  NOT NULL,
    published_by    VARCHAR(64),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (skill_code, version)
);

CREATE INDEX idx_skill_version_latest ON sys_skill_version (skill_code, created_at DESC);

-- 自动检查明细（危险能力 / 额外网络调用 / manifest 完整性 / 绑定接口最小性）
CREATE TABLE skill_check (
    id               BIGSERIAL,
    skill_version_id BIGINT      NOT NULL REFERENCES sys_skill_version(id),
    check_code       VARCHAR(64) NOT NULL,
    severity         VARCHAR(16) NOT NULL,                    -- blocking / warning
    passed           BOOLEAN     NOT NULL,
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);

-- 人工评审记录（绑定接口是否最小、导出字段是否合规）
CREATE TABLE skill_review (
    id               BIGSERIAL,
    skill_version_id BIGINT      NOT NULL REFERENCES sys_skill_version(id),
    decision         VARCHAR(16) NOT NULL,                    -- approved / rejected
    reviewer         VARCHAR(64) NOT NULL,
    reason           TEXT,
    exported_columns JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
);

-- 修正 V1 的一处类型错配：平台口径里 userId = sys_user.username（VARCHAR，§4.9），
-- 而 skill_api.approved_by 当初按「数值主键」写成了 BIGINT，写入必然失败。
-- 发布动作要在这里记「谁批准的绑定」，所以按平台口径统一成 VARCHAR。
-- 该列此前无任何代码写入（仅 M3 发布使用），无历史数据需要迁移。
ALTER TABLE skill_api ALTER COLUMN approved_by TYPE VARCHAR(64) USING approved_by::text;
