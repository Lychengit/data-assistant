package com.djzy.assistant.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把验签失败写进结构化日志（供日志采集做告警）。 */
public final class LoggingSecurityAuditLog implements SecurityAuditLog {

    private static final Logger log = LoggerFactory.getLogger(LoggingSecurityAuditLog.class);

    private final String serviceName;

    public LoggingSecurityAuditLog(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public void record(VerificationFailure failure) {
        log.warn("SIGNATURE_VERIFY_FAILED service={} keyId={} reason={} path={} peer={} timestamp={}",
                serviceName,
                failure.keyId(),
                failure.reasonIfPresent().map(Enum::name).orElse("UNKNOWN"),
                failure.path(),
                failure.peer(),
                failure.timestampHeader());
    }
}
