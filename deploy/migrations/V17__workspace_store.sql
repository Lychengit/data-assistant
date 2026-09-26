-- 工作区共享表（H-04）：多实例部署时，agent 的工作区文件存在这里，而不是各存各的本地磁盘。
--
-- 什么时候用得上：AgentScope 的工作区是一块文件空间（技能 SKILL.md 与配套脚本、记忆、会话痕迹都住在里面）。
-- 单实例时它就是本机的一个目录；多实例时如果还是本地目录，就会变成「A 机器上写的技能，B 机器上看不见」。
-- 这张表把这块空间搬到 PG 上，于是任意实例写、任意实例读，看到的是同一份（agent-service.workspace-store=jdbc 时才生效）。
--
-- 表结构照抄框架自己的建表语句（agentscope-extensions-jdbc 的 PostgresDialect#storeCreateTableDdls），一个字符都不改：
-- 读写这张表的 SQL 是框架的，结构跟着框架走才不会对不上。
-- 为什么不让框架自己建：见 V16 的同款理由——DDL 只能有一处真相，而且多实例同时启动时运行期 CREATE TABLE 会互相撞；
-- 应用侧装配时会确认表在，缺表直接启动失败（这正是我们要的失败方式）。
--
-- 表名 agentscope_store = 框架固定前缀 agentscope_ + store（方言的 storeTableName() 决定，可用 storeTableName 覆盖）；
-- 平台不改名，改了迁移脚本与框架 SQL 两处就对不上。
-- namespace_path 是「目录」：框架按隔离范围把 (userId, sessionId) 编成命名空间；
-- item_key 是目录里的文件名；value_json 是文件内容（框架自己编的 JSON）；version 是乐观并发版本（CAS 用）。
--
-- 项目未上线：直接建表，不写数据搬迁。上线后再改这张表就必须写真正的迁移。

CREATE TABLE IF NOT EXISTS agentscope_store (
    namespace_path VARCHAR(2048) NOT NULL,
    item_key       VARCHAR(255)  NOT NULL,
    value_json     TEXT          NOT NULL,
    version        BIGINT        NOT NULL,
    updated_at     BIGINT        NOT NULL,
    PRIMARY KEY (namespace_path, item_key)
);

-- 按目录整批读（ls / glob / 前缀搜索）走这条索引，与框架自己的建表语句保持一致。
CREATE INDEX IF NOT EXISTS agentscope_store_namespace_idx ON agentscope_store (namespace_path);

COMMENT ON TABLE agentscope_store IS '工作区共享存储（H-04，框架 BaseStore 契约）：(命名空间, 文件名) → JSON 文件内容';
