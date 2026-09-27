package com.djzy.assistant.iface.doctor.repo;

import java.time.Instant;

/**
 * 工件登记（§18.4.5 W3 / §18.5.3）：**每产出一份文件都登记它是什么、多大、被谁产出的、到什么时候失效**。
 *
 * <p>为什么接口服务要自己记这一笔（而不是让调用方记）：W2/W3 是一个整体——「写进对象存储」与
 * 「留下一条可查的工件记录」必须同生共死。让调用方去记，等于把「文件在、记录不在」这种
 * 事后查不清的状态留给运维：审计问「这份绩效表是谁导的」，两边都答不上来。
 *
 * <p>表用的是 V1 就建好的 {@code artifact}：它本来就是为「上传产物」准备的
 * （{@code artifact_id / session_id / name / size_bytes / locator / expires_at}）。
 */
public interface ArtifactRegistry {

    /**
     * 登记一份工件。
     *
     * @param artifactId 工件编号（调用方生成，便于在同一事务/日志里串联）
     * @param sessionId 归属会话；无会话（脚本/直连导出）时可为 null
     * @param name 展示名（已净化，不含路径）
     * @param sizeBytes 字节数
     * @param locator 对象存储里的定位串（{@code s3://bucket/key} 这类，供运维直接去存储侧核对）
     * @param expiresAt 链接/对象保留到期时间（§19.10 默认 7 天）
     */
    void register(
            String artifactId,
            String sessionId,
            String name,
            long sizeBytes,
            String locator,
            Instant expiresAt);
}