package com.djzy.assistant.common.confirm;

import java.time.Instant;
import java.util.Objects;

/**
 * 待确认项（{@code pending_confirm}）：技能启动前**一次**确认（§19.9），批准即批准整条链路。
 *
 * @param confirmId 确认 id（W1 校验的凭据）
 * @param sessionId 会话 id
 * @param turnId 轮次 id
 * @param userId 归属用户（防张冠李戴）
 * @param action 被确认的动作
 * @param summary 给用户看的摘要
 * @param status 状态
 * @param expiresAt 过期时间（§19.10 保留期）
 */
public record ConfirmRecord(
        String confirmId,
        String sessionId,
        String turnId,
        String userId,
        String action,
        String summary,
        ConfirmStatus status,
        Instant expiresAt) {

    public ConfirmRecord {
        Objects.requireNonNull(confirmId, "confirmId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
    }
}
