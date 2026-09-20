package com.djzy.assistant.common.permission;

/**
 * 网关单点判定结论（§4.8 节点 E）。
 *
 * <p>只有放行 / 拒绝与拒绝原因：数据范围不再由网关计算，而是接口服务基于登录人自己推导，
 * 所以这里没有范围可下发（以前那版把范围塞进判定结论，是范围唯一来源必须经过网关的前提）。
 *
 * @param decision 放行 / 拒绝
 * @param reason 拒绝原因（仅审计，不下发前端）
 */
public record ApiCallDecision(PermissionDecision decision, String reason) {

    public static ApiCallDecision allow() {
        return new ApiCallDecision(PermissionDecision.ALLOW, null);
    }

    public static ApiCallDecision deny(String reason) {
        return new ApiCallDecision(PermissionDecision.DENY, reason);
    }

    public boolean allowed() {
        return decision == PermissionDecision.ALLOW;
    }
}