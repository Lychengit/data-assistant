package com.djzy.assistant.common.security;

import java.util.Optional;

/** 被调方密钥表端口：只保存「允许调用我的那些服务的 keyId → sharedSecret」（§20.1.2）。 */
public interface SecretResolver {

    Optional<String> secretFor(String keyId);
}
