package com.djzy.assistant.management.repo;

import java.util.List;
import java.util.Optional;

/**
 * M2 角色/接口授权配置端口（**只写 PG、零缓存**，§0.3-4：改动立即生效，不存在缓存漂移）。
 *
 * <p>写操作由 {@code RoleApiAdminService} 负责「先读原值 → 再写 → 记账」，因此这里只做单表读写。
 *
 * <p>接口用 {@code api_id}（{@code sys_api.id}）指代——授权是"这一行"的授权，不是"某个名字"的授权；
 * 用名字授权会把接口改名变成一次静默的越权或失权。
 */
public interface RoleApiAdminRepository {

    List<RoleApiGrantView> listByRole(String roleCode);

    Optional<RoleApiGrantView> find(String roleCode, long apiId);

    /**
     * 新增授权（已存在则无操作，天然幂等）。
     *
     * @throws IllegalArgumentException 角色或接口不存在
     */
    void grant(String roleCode, long apiId);

    /** @return 是否真的删掉了一行（用于判断「撤销」是不是空操作）。 */
    boolean delete(String roleCode, long apiId);
}