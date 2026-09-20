package com.djzy.assistant.management.config;

import com.djzy.assistant.common.security.SecretResolver;
import com.djzy.assistant.common.security.StaticSecretResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 启动期密钥自检（§20.1.5）：缺少令牌签名密钥即**拒绝启动**，绝不签到一半才发现签不出来。 */
@Component
public class StartupSecretCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretCheck.class);

    private final ManagementProperties properties;

    public StartupSecretCheck(ManagementProperties properties) {
        this.properties = properties;
    }

    /** 令牌签名密钥解析器（唯一来源，避免各处各读一份配置）。 */
    public static SecretResolver resolverOf(ManagementProperties properties) {
        return StaticSecretResolver.of(properties.getSecrets());
    }

    @PostConstruct
    void verify() {
        if (!properties.isRequireSecrets()) {
            log.warn("management-service.require-secrets=false：跳过密钥自检（仅限受控的本地联调环境）");
            return;
        }
        String secret = properties.getSecrets().get(properties.getTokenKeyId());
        if (properties.getTokenKeyId() == null
                || properties.getTokenKeyId().isBlank()
                || secret == null
                || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 management-service.secrets[" + properties.getTokenKeyId() + "]（令牌签名密钥），拒绝启动（§20.1.5）");
        }
        log.info(
                "管理后台密钥自检通过：tokenKeyId={} tokenTtl={} refreshTtl={}",
                properties.getTokenKeyId(),
                properties.getTokenTtl(),
                properties.getRefreshTtl());
    }
}
