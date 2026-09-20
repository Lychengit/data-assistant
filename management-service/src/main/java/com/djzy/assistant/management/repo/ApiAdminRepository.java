package com.djzy.assistant.management.repo;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiRoute;
import java.util.List;
import java.util.Optional;

/**
 * 接口注册读写（{@code sys_api}，§18.4.6 M4）。
 *
 * <p>**身份是三元组**：{@code (service, http_method, http_path)} 唯一确定一行；{@code id} 只是
 * 授权比对（{@code role_api.api_id}）用的代理键。这里因此有两种查法——按 {@code id}（管理端点某一行）
 * 与按 {@link ApiRoute}（对账、绑定）。
 *
 * <p>**没有接口编码、没有列白名单、没有数据范围**：编码是"第二份清单"，代码里读不出来、路由也用不上；
 * 返回哪些列由接口自己的 SQL 写死；数据范围在接口服务内基于登录人推导（§19.1）。
 */
public interface ApiAdminRepository {

    List<ApiDescriptor> list();

    Optional<ApiDescriptor> findById(long id);

    Optional<ApiDescriptor> findByRoute(ApiRoute route);

    /** 新增或覆盖（按三元组定位，命中即更新、未命中即插入）。 */
    void upsert(ApiDescriptor descriptor);

    boolean setEnabled(long id, boolean enabled);

    /** 物理删除仅用于「刚登记错、还没被任何角色授权」的接口；已被授权时应停用而不是删除。 */
    boolean delete(long id);
}