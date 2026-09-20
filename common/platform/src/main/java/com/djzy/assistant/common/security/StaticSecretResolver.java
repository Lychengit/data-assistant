package com.djzy.assistant.common.security;

import java.util.Map;
import java.util.Optional;

/**
 * 静态密钥表（部署期注入，§20.1.2 / §20.1.5）。
 *
 * <p>被调方只保存「允许调用我的那些服务」的 {@code keyId → shared_secret}；密钥来自环境变量 /
 * docker-compose secret，**不写进代码、不进 git、不进日志**——因此本类只接受外部注入的映射，
 * 不提供任何内置默认值。
 */
public final class StaticSecretResolver implements SecretResolver {

    private final Map<String, String> secrets;

    private StaticSecretResolver(Map<String, String> secrets) {
        this.secrets = Map.copyOf(secrets);
    }

    public static StaticSecretResolver of(Map<String, String> secrets) {
        return new StaticSecretResolver(secrets == null ? Map.of() : secrets);
    }

    @Override
    public Optional<String> secretFor(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(secrets.get(keyId));
    }

    public int size() {
        return secrets.size();
    }
}
