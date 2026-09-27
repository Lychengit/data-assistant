package com.djzy.assistant.management.repo;

import java.util.List;

/**
 * 角色只读端口（{@code sys_role}）。
 *
 * <p>与 {@link RoleApiAdminRepository} / {@link RoleSkillAdminRepository} 不同，这里**没有写操作**：
 * 角色是授权的宾语，不是授权本身——管理端配的是"哪个角色能用什么"，不是"有哪些角色"。
 */
public interface RoleAdminRepository {

    /** 全量角色，按 {@code id} 排（新建的角色排在后面，和账号体系里的创建顺序一致）。 */
    List<RoleView> listAll();
}
