package com.djzy.assistant.iface.doctor.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 启动期密钥自检（§20.1.5）：接口服务只应接受网关的连接，缺密钥拒绝启动。 */
@Component
public class StartupSecretCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretCheck.class);

    private final DoctorInterfaceProperties properties;

    public StartupSecretCheck(DoctorInterfaceProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        if (!properties.isRequireSecrets()) {
            log.warn("interface-doctor.require-secrets=false：跳过密钥自检（仅限受控的本地联调环境）");
            return;
        }
        if (properties.getAllowedCallers().isEmpty()
                || properties.getAllowedCallers().values().stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new IllegalStateException("缺少 interface-doctor.allowed-callers（网关密钥），拒绝启动（§20.1.5）");
        }
        log.info("接口服务密钥自检通过：allowedCallers={} nonceStore={}",
                properties.getAllowedCallers().keySet(), properties.getNonceStore());
    }
}
