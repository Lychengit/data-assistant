package com.djzy.assistant.common.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 「审计读」审计记录（{@code audit_read_audit}，§20.4）。
 *
 * <p>审计记录本身是敏感数据：**谁在何时、用什么条件查了审计**必须留痕，否则无法回答
 * 「管理员有没有翻看与自己无关的审计」。与业务数据访问审计（{@link DataAccessAuditEntry}）
 * 分开两张表：事由不同，将来保留策略也不同（§20.4）。
 *
 * @param who 管理员账号（userId）
 * @param action 动作（trace / data-access / overview …）
 * @param params 查询条件快照（**必须含时间范围**，§20.5 留分区裁剪口子）
 * @param outcome 结果：放行 / 拒绝 / 执行失败
 * @param rowCount 返回行数
 * @param reason 失败或拒绝原因
 * @param createdAt 发生时间
 */
public record AuditReadEntry(
        String who,
        String action,
        Map<String, Object> params,
        Outcome outcome,
        int rowCount,
        String reason,
        Instant createdAt) {

    /** 审计读结果。 */
    public enum Outcome {
        ALLOW,
        DENY,
        ERROR
    }

    public AuditReadEntry {
        // 同 ConfigAuditEntry：查询条件里 null 有含义（未按该条件过滤），不能被 Map.copyOf 的 null 检查拦下
        params = params == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params));
        outcome = outcome == null ? Outcome.ALLOW : outcome;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
