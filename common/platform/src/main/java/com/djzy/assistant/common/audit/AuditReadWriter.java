package com.djzy.assistant.common.audit;

/**
 * 审计读写入端口（§20.4）。
 *
 * <p>用**可写账号**写入：审计内容走只读账号查出，但「查了审计」这件事必须落库，
 * 所以读写两条连接分开（§18.4.6 平台元数据出口）。
 */
@FunctionalInterface
public interface AuditReadWriter {

    void write(AuditReadEntry entry);
}
