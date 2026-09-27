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

    /**
     * JDK 内部开关：关掉它，{@code ProcessBuilder} 才会把参数里的双引号转义成 {@code \"}。
     *
     * <p>沙箱要在 **Windows 宿主**上起 {@code docker exec ... sh -c <命令>}。JDK 默认「允许歧义命令行」
     * 时不转义内嵌引号，宿主的命令行解析会把它们当定界符吃掉，容器收到的命令已经缺了引号
     * （2026-09-27 实测：{@code print("a", {"k": 1})} 到容器里变成 {@code print(a, {k: 1})}，
     * Python 直接 SyntaxError；中文与引号一起坏掉）。关掉之后，引号能原样送达容器。
     *
     * <p>它必须在**任何进程被拉起之前**生效，所以放在 {@code main} 的第一句——
     * {@code ProcessImpl} 只在首次建进程时才读这个属性，这里设置仍然赶得上（已实测）。
     */
    private static final String PROCESS_QUOTE_ESCAPING_PROPERTY = "jdk.lang.Process.allowAmbiguousCommands";

    public static void main(String[] args) {
        System.setProperty(PROCESS_QUOTE_ESCAPING_PROPERTY, "false");
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
