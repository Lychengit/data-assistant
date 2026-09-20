package com.djzy.assistant.common.web;

/**
 * 验签失败审计端口（§20.1.4-7：失败必须写审计 + 告警，绝不静默）。
 *
 * <p>网关落到 PG permission_audit；接口服务落到本地安全日志 + 告警
 * （接口服务的权限审计由网关权威承载，§20.4）。
 */
@FunctionalInterface
public interface SecurityAuditLog {

    void record(VerificationFailure failure);
}
