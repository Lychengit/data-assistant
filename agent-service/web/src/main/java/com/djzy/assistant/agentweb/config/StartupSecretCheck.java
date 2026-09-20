package com.djzy.assistant.agentweb.config;

import com.djzy.assistant.common.security.SecretResolver;
import com.djzy.assistant.common.security.StaticSecretResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 启动期密钥自检（§20.1.5）：令牌校验密钥缺失即**拒绝启动**。
 *
 * <p>接网关时还要校验「调用网关的服务密钥」——没有它就到不了数据面，
 * 与其在用户提问时才 500，不如启动即失败。
 */
@Component
public class StartupSecretCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretCheck.class);

    private final AgentServiceProperties properties;

    public StartupSecretCheck(AgentServiceProperties properties) {
        this.properties = properties;
    }

    /** 密钥解析器（唯一来源，避免各处各读一份配置）。 */
    public static SecretResolver resolverOf(AgentServiceProperties properties) {
        return StaticSecretResolver.of(properties.getSecrets());
    }

    @PostConstruct
    void verify() {
        if (!properties.isRequireSecrets()) {
            log.warn("agent-service.require-secrets=false：跳过密钥自检（仅限受控的本地联调环境）");
            return;
        }
        require(properties.getTokenKeyId(), "agent-service.secrets[{}]（登录令牌校验密钥）");
        if (properties.gatewayConfigured()) {
            require(properties.getGatewayKeyId(), "agent-service.secrets[{}]（调用网关的服务密钥）");
        }
        log.info(
                "agent-service 密钥自检通过：tokenKeyId={} runtimeId={} gatewayUrl={} ticketStore={}",
                properties.getTokenKeyId(),
                properties.getRuntimeId(),
                properties.gatewayConfigured() ? properties.getGatewayUrl() : "(未配置，工具清单为空)",
                properties.getTicketStore());
    }

    private void require(String keyId, String template) {
        String secret = keyId == null ? null : properties.getSecrets().get(keyId);
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少 " + template.replace("{}", String.valueOf(keyId)) + "，拒绝启动（§20.1.5）");
        }
    }
}
