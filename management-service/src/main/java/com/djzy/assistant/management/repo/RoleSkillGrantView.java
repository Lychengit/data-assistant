package com.djzy.assistant.management.repo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条角色 → 技能授权（{@code role_skill}，§4.2 / §4.7 / §18.4.6）。
 *
 * <p>授权回答「这个角色**能不能看到并使用**这个技能」，而这是一件事的两面：技能里绑定并评审通过的接口
 * （{@code skill_api}）**随技能一起授出**，角色不必再逐个接口直配一遍（§4.6 / §19.1 并集口径，ADR-19）。
 * 想让技能真正跑起来，要确认的只是这些接口确实绑在了技能上——网关的能力集仍是唯一判定点（§4.8 节点 E）。
 *
 * @param roleCode 角色编码
 * @param skillId 技能行 id（{@code sys_skill.id}）
 * @param skillCode 技能编码（{@code sys_skill.skill_code}，技能的身份，包清单与工作区目录用的都是它）
 * @param skillName 展示名（可改，别拿它当身份）
 * @param canView 是否可见（{@code role_skill.can_view}）
 */
public record RoleSkillGrantView(
        String roleCode, long skillId, String skillCode, String skillName, boolean canView) {

    /** 一行式标识（审计与展示都用它）。 */
    public String display() {
        return skillCode + (canView ? "" : "（不可见）");
    }

    /** 审计用的规范化快照。 */
    public Map<String, Object> toAuditMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("roleCode", roleCode);
        map.put("skillId", skillId);
        map.put("skillCode", skillCode);
        map.put("skillName", skillName);
        map.put("canView", canView);
        return map;
    }
}