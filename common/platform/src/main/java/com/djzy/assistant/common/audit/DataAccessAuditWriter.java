package com.djzy.assistant.common.audit;

/** 数据访问审计写入端口：实现必须同步直写 PG（一条不漏），失败即告警（§0.3-3 / §20.4）。 */
@FunctionalInterface
public interface DataAccessAuditWriter {

    void write(DataAccessAuditEntry entry);
}
