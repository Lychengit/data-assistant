package com.djzy.assistant.common.identity;

import java.util.Optional;

/** 登录令牌校验端口（网关 G1 使用，§20.1.1）。 */
@FunctionalInterface
public interface UserTokenVerifier {

    /** @return 校验通过返回身份；签名不对 / 过期 / 算法不符一律 {@link Optional#empty()}。 */
    Optional<UserIdentity> verify(String token);

    /** 解析 {@code Authorization: Bearer xxx} 头。 */
    default Optional<UserIdentity> verifyAuthorizationHeader(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String prefix = "Bearer ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String token = header.substring(prefix.length()).trim();
        return token.isEmpty() ? Optional.empty() : verify(token);
    }
}
