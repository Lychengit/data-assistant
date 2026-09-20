package com.djzy.assistant.common.identity;

import java.util.Objects;

/**
 * 账号记录（{@code sys_user}，§4.2 / §18.4.6 M1）。
 *
 * <p>{@code passwordHash} 只在登录校验时使用，**不得写入日志、不得下发前端**。
 *
 * @param userId 用户 id（**等于 {@code sys_user.username}**：全系统透传的就是这个可读标识，
 *     权限库与用户状态查询都以它为键，见 {@code JdbcPermissionRepository.roleIdsOf(username)}）
 * @param username 登录名
 * @param passwordHash 口令哈希（{@link PasswordHasher} 格式）
 * @param displayName 展示名
 * @param active 是否 active（停用立即失效，§19.4）
 */
public record UserAccount(
        String userId, String username, String passwordHash, String displayName, boolean active) {

    public UserAccount {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
    }
}
