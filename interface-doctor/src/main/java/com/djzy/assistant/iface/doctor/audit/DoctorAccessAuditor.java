package com.djzy.assistant.iface.doctor.audit;

import com.djzy.assistant.common.api.ApiResult;
import com.djzy.assistant.common.api.CallerInfo;
import com.djzy.assistant.common.audit.DataAccessAuditEntry;
import com.djzy.assistant.common.audit.DataAccessAuditWriter;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * I6 记账（§18.4.5 / §20.4）：数据访问是否合规**以接口服务为权威源**，每次访问一条不漏。
 *
 * <p>写入失败**不得影响请求结果**，但必须 ERROR 告警（§0.3-3 合规审计不参与「可丢」取舍）。
 *
 * <p>调用方是 {@link RegisteredApiAspect}，接口代码不直接用它——接口自己记的话，
 * 新加一个接口忘了写那一行不会有任何提示，而审计恰恰是靠"一条不漏"才有意义。
 */
@Component
public class DoctorAccessAuditor {

    private static final Logger log = LoggerFactory.getLogger(DoctorAccessAuditor.class);

    private final DataAccessAuditWriter writer;
    private final String serviceName;

    public DoctorAccessAuditor(
            DataAccessAuditWriter writer, @Value("${spring.application.name}") String serviceName) {
        this.writer = writer;
        this.serviceName = serviceName;
    }

    public void allow(
            CallerInfo caller, String callerKeyId, String httpMethod, String httpPath, Object result) {
        if (!(result instanceof ApiResult api)) {
            // 约定被破坏时不能静默：悄悄不记账等于这条访问在合规审计里不存在。
            log.error("接口未返回 ApiResult，本次未记账（§0.3-3）：path={} 返回类型={}",
                    httpPath, result == null ? "null" : result.getClass().getName());
            return;
        }
        write(caller, callerKeyId, httpMethod, httpPath, DataAccessAuditEntry.Outcome.ALLOW, null,
                api.rowCount(), api.truncated());
    }

    public void deny(CallerInfo caller, String callerKeyId, String httpMethod, String httpPath, String reason) {
        write(caller, callerKeyId, httpMethod, httpPath, DataAccessAuditEntry.Outcome.DENY, reason, 0, false);
    }

    public void error(CallerInfo caller, String callerKeyId, String httpMethod, String httpPath, String reason) {
        write(caller, callerKeyId, httpMethod, httpPath, DataAccessAuditEntry.Outcome.ERROR, reason, 0, false);
    }

    private void write(
            CallerInfo caller,
            String callerKeyId,
            String httpMethod,
            String httpPath,
            DataAccessAuditEntry.Outcome outcome,
            String reason,
            int rowCount,
            boolean truncated) {
        try {
            writer.write(new DataAccessAuditEntry(
                    caller.requestId(),
                    caller.traceId(),
                    callerKeyId,
                    caller.userId(),
                    serviceName,
                    httpMethod,
                    httpPath,
                    caller.skillCode(),
                    // 数据范围已不在平台侧配置：范围管理落地后，由接口把自己实际用的过滤条件填这里。
                    // 在那之前这一栏是空的，别把它当成"这次没有范围限制"的证据（§20.4）。
                    java.util.Map.of(),
                    outcome,
                    reason,
                    rowCount,
                    truncated,
                    Instant.now()));
        } catch (RuntimeException e) {
            log.error("I6 数据访问审计写入失败（§0.3-3 必须告警）：requestId={} userId={} path={} outcome={}",
                    caller.requestId(), caller.userId(), httpPath, outcome, e);
        }
    }
}