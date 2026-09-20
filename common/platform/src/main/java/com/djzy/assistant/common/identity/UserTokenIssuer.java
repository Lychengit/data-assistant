package com.djzy.assistant.common.identity;

import java.util.Map;

/**
 * 登录令牌签发端口（management-service M1 登录成功后签发，§19.4）。
 *
 * <p>有效期必须 ≤15 分钟（配合 refresh token），不签发「一次管一天」的长效令牌。
 */
@FunctionalInterface
public interface UserTokenIssuer {

    /**
     * @param userId 用户 id（写进 {@code sub}）
     * @param extraClaims 扩展声明（可为 {@code null}）
     * @return 签名后的 JWT
     */
    String issue(String userId, Map<String, Object> extraClaims);
}
