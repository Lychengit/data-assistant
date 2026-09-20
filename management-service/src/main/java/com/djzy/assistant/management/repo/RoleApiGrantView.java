package com.djzy.assistant.management.repo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条角色 → 接口授权（{@code role_api}，§4.2 / §18.4.6 M2）。
 *
 * <p>**没有 scope**：数据范围不再随授权配置。授权回答的是「这个角色能不能调这个接口」，
 * 能看多大范围由接口服务基于登录人自己推导（§19.1）——把范围挂在授权行上，等于让权限库去
 * 猜业务表的结构，接口一复杂就必然要改权限库。
 *
 * @param roleCode 角色编码
 * @param apiId 接口注册行 id（{@code sys_api.id}，授权比对的键）
 * @param httpMethod HTTP 方法
 * @param httpPath 接口路径
 */
public record RoleApiGrantView(String roleCode, long apiId, String httpMethod, String httpPath) {

    /** 一行式标识（审计与展示都用它，而不是一个看不懂的 id）。 */
    public String display() {
        return httpMethod + " " + httpPath;
    }

    /** 审计用的规范化快照。 */
    public Map<String, Object> toAuditMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("roleCode", roleCode);
        map.put("apiId", apiId);
        map.put("httpMethod", httpMethod);
        map.put("httpPath", httpPath);
        return map;
    }
}