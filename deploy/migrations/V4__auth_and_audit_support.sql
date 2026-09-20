-- 登录态与「审计读」支撑（§19.4 / §20.4 / §18.4.6 M6）。
-- 与 V1 同一套迁移：新环境从空库跑到最新版本即可（§20.8）。

-- ============ refresh token（§19.4）============
-- 只存 **哈希**，不存原文：令牌一旦泄漏，库里的记录不可被直接重放。
-- 一次性消费靠 `revoked_at IS NULL` 的条件更新（并发重放只有一方能改成功）。
CREATE TABLE refresh_token (
    token_hash VARCHAR(64) PRIMARY KEY,                    -- SHA-256(令牌原文)
    user_id    VARCHAR(64) NOT NULL,                       -- = sys_user.username（§19.4 用户状态每请求直查）
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_refresh_token_user ON refresh_token (user_id, created_at);

-- ============ 审计读审计（§20.4：谁在何时查了审计）============
-- 与 `data_access_audit`（数据访问）/ `permission_audit`（判定）/ `config_audit`（配置变更）**分开**：
-- 这四类事由不同，混在一张表里将来查询与保留策略都会互相牵连。
-- 写入走可写账号，读取范围（谁查了什么条件）与业务审计内容分开。
CREATE TABLE audit_read_audit (
    id         BIGSERIAL,
    who        VARCHAR(64)  NOT NULL,                      -- 管理员账号
    action     VARCHAR(64)  NOT NULL,                      -- trace / data-access / overview
    params     JSONB,                                      -- 查询条件快照（时间范围必填）
    outcome    VARCHAR(16)  NOT NULL,                      -- ALLOW / DENY / ERROR
    row_count  INT,
    reason     VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)                           -- 主键带时间列，为月分区留口子（§20.5）
);

CREATE INDEX ix_audit_read_audit_who_created ON audit_read_audit (who, created_at);

-- ============ 演示账号（骨架期）============
-- 口令哈希 = PBKDF2-HMAC-SHA256（PasswordHasher），固定盐仅用于演示种子；真实部署由账号管理写入。
-- admin/admin123、alice/alice123、gone（disabled，用于验证「停用立即失效」，口令同 alice）。
INSERT INTO sys_role (role_code, role_name, remark) VALUES
    ('admin', '系统管理员', '仅用于管理后台入口（ §20.4 ）'),
    ('boss',  '大区经理',   '演示用业务角色')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO sys_dept (name, path, sort) VALUES ('心内科', '心内科', 10), ('呼吸科', '呼吸科', 20)
ON CONFLICT DO NOTHING;

INSERT INTO sys_user (username, password_hash, display_name, status, dept_id)
SELECT v.username, v.password_hash, v.display_name, v.status, d.id
  FROM (VALUES
        ('admin', 'pbkdf2$210000$ZGVtby1zZWVkLXNhbHQtMQ$K77tBEhbW8OdRspFMJPm+qO/4hlwXPLP2GggxeG4TTI', '系统管理员', 'active', '心内科'),
        ('alice', 'pbkdf2$210000$ZGVtby1zZWVkLXNhbHQtMg$BwUNs0RSoSAld8rgWszI5RTM/NKRr17CjxngL0rYovM', '张医生',   'active', '心内科'),
        ('gone',  'pbkdf2$210000$ZGVtby1zZWVkLXNhbHQtMg$BwUNs0RSoSAld8rgWszI5RTM/NKRr17CjxngL0rYovM', '已停用',   'disabled', '呼吸科')
       ) AS v(username, password_hash, display_name, status, dept_name)
  LEFT JOIN sys_dept d ON d.name = v.dept_name
ON CONFLICT (username) DO NOTHING;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
  FROM sys_user u
  JOIN sys_role r ON r.role_code = CASE WHEN u.username = 'admin' THEN 'admin' ELSE 'boss' END
 WHERE u.username IN ('admin', 'alice', 'gone')
ON CONFLICT DO NOTHING;
