package com.djzy.assistant.common.identity;

/**
 * 用户账号状态读取端口（{@code sys_user.status}）。
 *
 * <p>§19.4：**网关每次请求查一次**——停用 / 离职立即失效；角色变更 / 撤权最多 15 分钟延迟生效
 * （等旧 token 过期，不引入 token 版本号）。
 */
@FunctionalInterface
public interface UserStatusPort {

    /** @return 账号存在且为 active。查不到按停用处理（fail-closed）。 */
    boolean isActive(String userId);
}
