package com.djzy.assistant.common.identity;

import java.util.Optional;

/** 账号读取端口（登录与刷新用，§18.4.6 M1）：实现只取数，口令比对由 {@link PasswordHasher} 唯一实现。 */
public interface UserAccountPort {

    /** @return 账号（含停用账号，由调用方决定按 401 统一处理）；查不到返回 empty。 */
    Optional<UserAccount> findByUsername(String username);

    /** 刷新令牌轮换时需要按 userId 复核账号状态（§19.4：停用立即失效）。 */
    Optional<UserAccount> findById(String userId);
}
