package com.djzy.assistant.agentweb;

import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * agent-service 接入层（§12.1 / §18.4.1 / §19.4）。
 *
 * <p>它只做三件事：**验身份**（JWT + 账号状态）、**发一次性入场券**（令牌不进 URL / 日志）、
 * **把运行时事件投影成 SSE**（§12.2）并支持断线续传（§19.4）。
 * 权限判定不在这里——工具可见性由网关单点判定（§19.7）；数据范围**既不在本节点、也不在网关**
 * 计算，由接口服务基于登录人自行推导（§19.1 / ADR-37）。
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentServiceProperties.class)
@EnableScheduling
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
