package com.djzy.assistant.common.api;

import java.util.Objects;

/**
 * 网关在信封里下发的**可信身份**（§19.7 / §20.1.1）。
 *
 * <p>为什么这些字段必须在**请求体**里而不是 HTTP 头里：签名串只覆盖
 * {@code 方法 + 路径 + query + 时间戳 + nonce + SHA256(请求体)}（{@code CanonicalRequest}），
 * 请求头不参与。放头里等于"任何能碰到接口服务的人都能把 userId 改成别人而签名照样通过"，
 * 而这正是整套服务间信任的地基。
 *
 * <p>这个 record 就是**线上格式**，所以不要在这里加 {@code isXxx()} / {@code getXxx()} 形状的派生方法：
 * Jackson 会把它们当属性一起写进 JSON，而接口服务开了 {@code fail-on-unknown-properties}（fail-closed），
 * 多一个键就整单拒收——这类事故在编译期和单测里都看不见，只会在联调时表现为"权限明明放行却查不到数据"。
 *
 * @param userId 网关推导出的可信身份（接口服务直接使用，不重解析）
 * @param requestId 请求编号（审计串联）
 * @param traceId 链路 id（OTel）
 * @param skillCode 技能发起时的技能编码
 * @param confirmId 写操作确认凭据（网关消费成功后才会下发）
 */
public record CallerInfo(String userId, String requestId, String traceId, String skillCode, String confirmId) {

    public CallerInfo {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
    }
}