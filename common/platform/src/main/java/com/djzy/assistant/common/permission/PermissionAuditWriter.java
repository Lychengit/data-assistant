package com.djzy.assistant.common.permission;

/** 权限审计写入端口：实现必须同步直写 PG（一条不漏），失败即告警（§0.3-3 / §11.4）。 */
@FunctionalInterface
public interface PermissionAuditWriter {

    void write(PermissionAuditEntry entry);
}
