package com.djzy.assistant.common.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 接口注册表读取端口（{@code sys_api}）。
 *
 * <p>网关 G2 用 {@link #findByRoute} 把「服务 + 方法 + 路径」解析成注册行——**这一查同时也是
 * 授权的主体**：查不到行就是 404，查到行才谈得上"这个用户有没有这把钥匙"。
 *
 * <p>接口服务**不用**它判权限——判定只在网关（§19.7）。它只在启动自检时读一次，做「本地路由 vs
 * 注册表」对账。
 */
public interface ApiRegistry {

    /** 按身份三元组精确匹配（大小写与前后空白都已归一）。 */
    Optional<ApiDescriptor> findByRoute(String service, String httpMethod, String httpPath);

    /**
     * 按注册行 id 批量取元数据（能力清单用）。
     *
     * <p>存在的理由：授权算出来的是 id 集合，而模型需要的是契约明细。分两步走比在权限库里
     * 重写一遍 {@code sys_api} 的列清单更不容易漂移。
     */
    List<ApiDescriptor> findByIds(Collection<Long> ids);
}