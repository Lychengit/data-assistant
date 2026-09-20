package com.djzy.assistant.management.service;

import java.util.List;

/**
 * 登录/刷新结果（§19.4）。
 *
 * @param token 访问令牌（JWT，≤15 分钟）
 * @param tokenType 固定 {@code Bearer}
 * @param expiresIn 访问令牌剩余秒数
 * @param refreshToken 刷新令牌（明文只在此刻下发，服务端只存哈希）
 * @param refreshExpiresIn 刷新令牌剩余秒数
 * @param userId 用户 id
 * @param displayName 展示名
 * @param roles 角色编码（**仅用于前端展示**；判定一律直查权限库，§19.4）
 */
public record AuthResult(
        String token,
        String tokenType,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn,
        String userId,
        String displayName,
        List<String> roles) {

    public AuthResult {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
