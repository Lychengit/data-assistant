package com.djzy.assistant.gateway.config;

import com.djzy.assistant.common.security.NonceStore;
import com.djzy.assistant.common.security.SecretResolver;
import com.djzy.assistant.common.security.ServiceVerifier;
import com.djzy.assistant.common.security.StaticSecretResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 启动期密钥自检（§20.1.5）：所需密钥缺失即**拒绝启动**——比静默降级安全。
 *
 * <p>同时把「当前用的是内存 nonce 存储（单实例）」这条部署前提打在启动日志里（§20.1.4 脚注）。
 */
@Component
public class StartupSecretCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretCheck.class);

    private final GatewayProperties properties;
    private final NonceStore nonceStore;

    public StartupSecretCheck(GatewayProperties properties, NonceStore nonceStore) {
        this.properties = properties;
        this.nonceStore = nonceStore;
    }

    @PostConstruct
    void verify() {
        if (!properties.isRequireSecrets()) {
            log.warn("gateway.require-secrets=false：跳过密钥自检（只允许在受控的本地联调环境使用）");
            return;
        }
        require(properties.getAllowedCallers(), "gateway.allowed-callers（允许调用网关的服务密钥）");
        requireSecret(properties.getTokenKeyId(), "登录令牌签名密钥");
        requireSecret(properties.getOutboundKeyId(), "网关对外签名密钥");
        log.info("网关密钥自检通过：allowedCallers={} outboundKeyId={} nonceStore={}",
                properties.getAllowedCallers().keySet(), properties.getOutboundKeyId(), nonceStore.getClass().getSimpleName());
    }

    private void requireSecret(String keyId, String what) {
        if (keyId == null || keyId.isBlank() || properties.getSecrets().getOrDefault(keyId, "").isBlank()) {
            throw new IllegalStateException("缺少「" + what + "」（keyId=" + keyId + "），拒绝启动（§20.1.5）");
        }
    }

    private static void require(java.util.Map<String, String> secrets, String what) {
        if (secrets == null || secrets.isEmpty() || secrets.values().stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new IllegalStateException("缺少「" + what + "」，拒绝启动（§20.1.5）");
        }
    }

    /** 供配置类复用：把密钥表包成解析器。 */
    public static SecretResolver resolverOf(GatewayProperties properties) {
        return StaticSecretResolver.of(properties.getSecrets());
    }

    public static ServiceVerifier verifierOf(GatewayProperties properties, NonceStore nonceStore) {
        return new ServiceVerifier(StaticSecretResolver.of(properties.getAllowedCallers()), nonceStore);
    }
}
