package com.djzy.assistant.common.permission;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 节点 A 的查询结果（§19.8 {@code GET /v1/permission/capabilities}，由网关提供）。
 *
 * <p>agent-service 只拿「看得见什么」：技能清单 + 接口契约。数据范围既不在这里，也不下发——
 * 它由接口服务基于登录人自行推导（§4.3：范围明细永不进入模型上下文）。
 *
 * @param userId 用户 id
 * @param viewableSkills 可触发技能（注册为 {@code skill_*} 工具）
 * @param apis 可调接口及其契约（注册为 {@code iface_*} 工具）
 * @param computedAt 计算时间（零缓存，每次直查 PG，§0.3-4）
 */
public record Capabilities(
        String userId, List<String> viewableSkills, List<ApiCapability> apis, Instant computedAt) {

    public Capabilities {
        Objects.requireNonNull(userId, "userId");
        viewableSkills = viewableSkills == null ? List.of() : List.copyOf(viewableSkills);
        apis = apis == null ? List.of() : List.copyOf(apis);
        computedAt = computedAt == null ? Instant.now() : computedAt;
    }
}