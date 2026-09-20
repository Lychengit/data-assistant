package com.djzy.assistant.common.config;

import java.time.Instant;
import java.util.Map;

/**
 * 配置变更审计（{@code config_audit}，§20.7）：**谁在何时把哪个配置从什么改成了什么**。
 *
 * <p>配置变更即时生效（零缓存直查），因此审计是事后追责的唯一依据——一次变更一条记录，
 * 不允许只记结果不记原值。
 *
 * @param who 操作者 userId
 * @param target 变更对象（role / skill / api / dictionary）
 * @param field 变更字段（如 {@code role_api:doctor_performance}）
 * @param before 变更前（新增时为 {@code null}）
 * @param after 变更后（撤销时为 {@code null}）
 * @param requestId 请求编号（与链路审计串联）
 * @param createdAt 发生时间
 */
public record ConfigAuditEntry(
        String who,
        String target,
        String field,
        Map<String, Object> before,
        Map<String, Object> after,
        String requestId,
        Instant createdAt) {

    public ConfigAuditEntry {
        before = snapshot(before);
        after = snapshot(after);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * 审计快照的不可变副本。
     *
     * <p>**不能用 {@code Map.copyOf}**：配置快照里的 {@code null} 是有意义的取值（未设置 / 未启用 / 已清空），
     * 而 {@code Map.copyOf} 遇到 null 值会抛 NPE——那会让「一条本来就该记的审计」把业务操作一起带崩。
     * 审计宁可原样保留 null，也不许丢信息。
     */
    private static Map<String, Object> snapshot(Map<String, Object> value) {
        return value == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value));
    }
}
