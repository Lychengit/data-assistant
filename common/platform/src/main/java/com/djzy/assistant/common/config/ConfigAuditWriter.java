package com.djzy.assistant.common.config;

/** 配置变更审计写入端口：与合规审计同样**同步直写 PG、一条不漏**（§20.7）。 */
@FunctionalInterface
public interface ConfigAuditWriter {

    void write(ConfigAuditEntry entry);
}
