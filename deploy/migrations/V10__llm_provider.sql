-- V10：LLM 供应商配置（ADR-14 / §20.1.6 / §20.7）
--
-- 为什么配置进库：「用哪个供应商、哪个模型、哪把 Key」是运行期可变配置，不是编译期常量。
-- 换供应商不该重新发版，所以由管理端页面维护，每次变更写 config_audit（§20.7）。
--
-- 为什么 Key 只存密文：§20.1.6 硬约束第 7 条——密钥不得进入 URL / 日志 / Trace /
-- 错误响应 / 模型上下文。明文入库等于把「一次库 dump」变成「一次密钥泄露」。
-- 这里存 AES-GCM 密文，解密密钥（KEK）由部署环境注入，库与密钥分家：
-- 拿得到库、拿不到运行环境，仍然解不出 Key。
--
-- key_hint 是**故意留的**：界面要回答「我现在配的是哪把 Key」，只回显尾部 4 位，
-- 够辨认、不够还原。
--
-- enabled 用部分唯一索引把「同时生效两个」**焊死**（fail-closed）：运行时的模型选择
-- 必须是确定的，不能出现「两个都开着、看谁先被选中」。换供应商由服务层在同一事务里
-- 做「关旧的、开新的」，数据库这一层只负责让非法状态插不进来。
--
-- 写入方：management-service（管理端直连库，§18.4.6）；读取方：agent-service（每轮取生效配置）。

CREATE TABLE sys_llm_provider (
    provider_id    VARCHAR(64)  NOT NULL,            -- 逻辑名：deepseek / qwen ...
    adapter        VARCHAR(32)  NOT NULL,            -- 适配器类型（映射到 ModelProvider，如 deepseek）
    base_url       VARCHAR(255) NOT NULL,            -- OpenAI 兼容端点
    model          VARCHAR(128) NOT NULL,            -- 模型名，如 deepseek-flash
    api_key_cipher TEXT         NOT NULL,            -- AES-GCM 密文（Base64），明文不落库
    key_hint       VARCHAR(64)  NOT NULL,            -- 仅回显：sk-****1234
    enabled        BOOLEAN      NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_id)
);

-- 「同一时刻至多一个生效」：第二个 enabled=true 直接插不进来 / 更新不过去。
CREATE UNIQUE INDEX ux_sys_llm_provider_enabled ON sys_llm_provider (enabled) WHERE enabled;

COMMENT ON TABLE sys_llm_provider IS 'LLM 供应商配置（ADR-14）：Key 只存密文，enabled 至多一行';
