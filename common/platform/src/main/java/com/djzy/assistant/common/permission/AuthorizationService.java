package com.djzy.assistant.common.permission;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限判定函数唯一实现（§4.6 / §4.9 / §19.1）：任何入口都调这里，不得各写一份。
 *
 * <pre>
 * rolesOf(user)            → 角色集（多角色并存）
 * viewableSkills(user)     → role_skill[其角色] ∪ personal(owner=本人)
 * canTrigger(user, skill)  → 节点 B / E 的技能触发校验
 * runtimeApis(skill)       → skill_api(approved=true)
 * apiSet(user)             → Σ(各角色 role_skill → skill_api) ∪ role_api    ← 并集公式唯一实现
 * decideApiCall(user, api) → 节点 E 单点判定（授权）
 * </pre>
 *
 * <p>口径要点（§19.1）：**授权取并集**；技能不携带范围，范围也不再由平台配置——
 * 数据可见范围由接口服务基于登录人自行推导，所以这里只有"有没有这把钥匙"，没有"能看多少数据"。
 * 无授权来源即拒绝，绝不退化成"放行但空数据"。
 */
public final class AuthorizationService {

    private final PermissionRepository repository;

    public AuthorizationService(PermissionRepository repository) {
        this.repository = repository;
    }

    public List<String> rolesOf(String userId) {
        return List.copyOf(repository.roleIdsOf(userId));
    }

    /** 节点 A：该用户可见（即可执行）的技能。 */
    public Set<String> viewableSkills(String userId) {
        List<String> roles = repository.roleIdsOf(userId);
        Set<String> skills = new LinkedHashSet<>(repository.skillCodesOfRoles(roles));
        skills.addAll(repository.personalSkillCodesOf(userId));
        return Set.copyOf(skills);
    }

    /** 节点 B / E：技能触发校验。 */
    public boolean canTrigger(String userId, String skillCode) {
        if (skillCode == null || skillCode.isBlank()) {
            return false;
        }
        List<String> roles = repository.roleIdsOf(userId);
        return repository.skillCodesOfRoles(roles).contains(skillCode)
                || repository.personalSkillCodesOf(userId).contains(skillCode);
    }

    /** 节点 C：技能执行器白名单（只暴露该技能自己声明且审核通过的接口）。 */
    public Set<Long> runtimeApis(String skillCode) {
        return Set.copyOf(repository.approvedApiIdsOfSkill(skillCode));
    }

    /** 授权并集公式唯一实现（§4.6）：Σ(角色配置技能 → skill_api) ∪ 角色直配接口。 */
    public Set<Long> apiSet(String userId) {
        List<String> roles = repository.roleIdsOf(userId);
        Set<Long> apis = new LinkedHashSet<>();
        for (String skillCode : repository.skillCodesOfRoles(roles)) {
            apis.addAll(repository.approvedApiIdsOfSkill(skillCode));
        }
        apis.addAll(repository.directApiIdsOfRoles(roles));
        return Set.copyOf(apis);
    }

    /** 节点 E：网关单点判定（授权）。 */
    public ApiCallDecision decideApiCall(String userId, long apiId, String skillCode) {
        List<String> roles = repository.roleIdsOf(userId);
        if (skillCode != null && !skillCode.isBlank()) {
            boolean triggerable = repository.skillCodesOfRoles(roles).contains(skillCode)
                    || repository.personalSkillCodesOf(userId).contains(skillCode);
            if (!triggerable) {
                return ApiCallDecision.deny("SKILL_NOT_TRIGGERABLE");
            }
            if (!repository.approvedApiIdsOfSkill(skillCode).contains(apiId)) {
                return ApiCallDecision.deny("API_NOT_IN_SKILL_WHITELIST");
            }
            return ApiCallDecision.allow();
        }
        if (!apiSet(userId).contains(apiId)) {
            return ApiCallDecision.deny("API_NOT_IN_USER_UNION");
        }
        return ApiCallDecision.allow();
    }

    /** 该技能是否声明了这个接口（技能执行器侧用，避免它自己去查接口编码）。 */
    public boolean skillDeclaresApi(String skillCode, long apiId) {
        return skillCode != null && !skillCode.isBlank() && runtimeApis(skillCode).contains(apiId);
    }
}