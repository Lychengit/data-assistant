package com.djzy.assistant.common.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 数据访问审计记录（I6，§18.4.5 / §20.4）：数据访问是否合规**以接口服务为权威源**。
 *
 * <p>硬约束（§0.3-3）：与 {@code permission_audit} 一样**直写 PG、一条不漏**，不参与
 * 「日志先行 + 异步落库」的取舍；写入失败必须告警，不允许静默丢弃。与 agent 侧
 * {@code tool_call} 以 {@code requestId} 关联，不一致即告警（§20.4）。
 *
 * <p>接口用「服务 + 方法 + 路径」而不是接口编码：编码已经不再是身份，审计要能回答
 * "到底访问了哪个端点"，而端点就是这三项。
 *
 * @param requestId 请求编号（全链路审计串联键）
 * @param traceId 链路 id（OTel）
 * @param callerKeyId I1 验签得到的调用方 keyId（不含密钥，§20.1.3）
 * @param userId 网关下发的可信身份
 * @param service 目标服务名
 * @param httpMethod HTTP 方法
 * @param httpPath 目标接口路径
 * @param skillCode 技能发起时的技能编码；直连调用为 {@code null}
 * @param scopeSnapshot 本次实际使用的数据范围快照
 * @param outcome 结果：放行 / 拒绝 / 执行失败
 * @param reason 结果原因（拒绝与失败必填）
 * @param rowCount 实际返回行数
 * @param truncated 是否因行数上限被截断
 * @param createdAt 发生时间
 */
public record DataAccessAuditEntry(
        String requestId,
        String traceId,
        String callerKeyId,
        String userId,
        String service,
        String httpMethod,
        String httpPath,
        String skillCode,
        Map<String, Object> scopeSnapshot,
        Outcome outcome,
        String reason,
        int rowCount,
        boolean truncated,
        Instant createdAt) {

    /** 审计结果（原因细节进 {@code reason}，不在这里膨胀）。 */
    public enum Outcome {
        ALLOW,
        DENY,
        ERROR
    }

    public DataAccessAuditEntry {
        scopeSnapshot = scopeSnapshot == null ? Map.of() : snapshot(scopeSnapshot);
        outcome = outcome == null ? Outcome.ALLOW : outcome;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * 审计快照的不可变副本（与 {@code PermissionAuditEntry} / {@code ConfigAuditEntry} 同一条口径）。
     *
     * <p>**不能用 {@code Map.copyOf}**：它拒绝 null 值，而快照里的 {@code null} 是有意义的取值
     * （范围为空、技能编码为 null…）。一个空字段让整条数据访问审计写不进去，
     * 就等于「越权拒绝」这类记录被静默丢掉，与本节「直写 PG、一条不漏」直接冲突。
     */
    private static Map<String, Object> snapshot(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value));
    }
}