package com.djzy.assistant.management.repo;

import java.util.List;
import java.util.Optional;

/**
 * 角色/技能授权配置端口（**只写 PG、零缓存**，§0.3-4：改动立即生效，不存在缓存漂移）。
 *
 * <p>写操作由 {@code RoleSkillAdminService} 负责「先读原值 → 再写 → 记账」，因此这里只做单表读写。
 *
 * <p><b>为什么用 {@code skill_code} 指代技能而不是 {@code skill_id}</b>（与
 * {@link RoleApiAdminRepository} 用 {@code api_id} 的理由正好相反）：接口的"身份"是注册行
 * （同名不同行是两回事），而技能的"身份"就是编码本身——包的清单、工作区的目录名、
 * 网关算能力集时用的键，全都是 {@code skill_code}；{@code sys_skill.name} 才是可改的展示名。
 * 拿展示名授权会变成一次静默的越权或失权，而编码不会。
 */
public interface RoleSkillAdminRepository {

    List<RoleSkillGrantView> listByRole(String roleCode);

    Optional<RoleSkillGrantView> find(String roleCode, String skillCode);

    /**
     * 新增授权（已存在则更新 {@code can_view}，天然幂等）。
     *
     * @throws IllegalArgumentException 角色或技能不存在
     */
    void grant(String roleCode, String skillCode, boolean canView);

    /** @return 是否真的删掉了一行（用于判断「撤销」是不是空操作）。 */
    boolean revoke(String roleCode, String skillCode);
}