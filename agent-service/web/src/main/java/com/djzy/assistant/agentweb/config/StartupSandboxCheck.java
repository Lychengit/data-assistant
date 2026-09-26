package com.djzy.assistant.agentweb.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 启动期沙箱自检（H-05）：**开了沙箱就必须真的有 Docker**，否则拒绝启动。
 *
 * <p>为什么不是"起不来就退回本机跑"：那样最坏的情况是——运维以为脚本跑在容器里被隔离了，
 * 实际上它以 agent 服务的身份跑在宿主机上，读得到环境变量、网络和权限范围内的一切。
 * 「以为隔离了、其实没隔离」比启动失败危险得多，所以这里选择**显式失败**。
 *
 * <p>自检内容刻意很浅：只确认 {@code docker version} 能拿到服务端版本（Docker 守护进程在线）。
 * 镜像存不存在、能不能拉，交给第一次真正用容器时的 {@code docker run}——那时失败也带着明确的
 * Docker 报错，比在启动期猜"这个镜像将来能不能拉下来"更准。
 *
 * <p>关着的时候什么都不做（默认就是关的）：这条自检不能给不开沙箱的部署增加任何前置条件。
 */
@Component
public class StartupSandboxCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSandboxCheck.class);

    /** 自检超时：够慢机器上的 docker CLI 回话，又不至于让启动卡到让人以为进程挂了。 */
    private static final int PROBE_TIMEOUT_SECONDS = 20;

    private final AgentServiceProperties properties;

    /**
     * 探测「本机 Docker 到底能不能用」：拿得到服务端版本就算能用，空串算不能用。
     *
     * <p>做成一个可替换的函数（而不是直接调私有方法）：这样用例能把「没有 Docker」这条分支也测到，
     * 不用为了测它去改 PATH 或真的把 Docker 停掉——那条分支恰恰是**最需要被守住**的一条
     * （它决定"宁可起不来，也不要假装隔离了"）。生产上永远用下面那个单参构造器。
     */
    private final java.util.function.Supplier<String> dockerProbe;

    /** 生产用的构造器；{@code @Autowired} 是必须的：有两个构造器时 Spring 不会自己去猜该用哪个。 */
    @Autowired
    public StartupSandboxCheck(AgentServiceProperties properties) {
        this(properties, StartupSandboxCheck::dockerServerVersion);
    }

    /** 供用例注入假探测；生产代码不要用这个构造器（见 {@link #dockerProbe}）。 */
    StartupSandboxCheck(AgentServiceProperties properties, java.util.function.Supplier<String> dockerProbe) {
        this.properties = properties;
        this.dockerProbe = dockerProbe;
    }

    @PostConstruct
    void verify() {
        AgentServiceProperties.Sandbox sandbox = properties.getSandbox();
        if (!sandbox.isEnabled()) {
            log.info("沙箱：关闭（命令不在容器里跑；打开见 agent-service.sandbox.enabled）");
            return;
        }
        String version = dockerProbe.get();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    "agent-service.sandbox.enabled=true，但拿不到 docker version：拒绝启动。"
                            + "请确认 Docker 已启动且 docker 命令在 PATH 上（本机安装与排障见 deploy/local-windows/README.md），"
                            + "或者把 agent-service.sandbox.enabled 关掉（默认就是关的）。"
                            + "不会退回本机执行：那样脚本会以 agent 服务的身份跑在宿主机上，属于"
                            + "\"以为隔离了、其实没隔离\"。");
        }
        log.info(
                "沙箱：开（Docker {}，镜像 {}，工作区 {}，共享前缀 {}）",
                version,
                sandbox.getImage(),
                sandbox.getWorkspaceRoot(),
                sandbox.getSharedPrefixes());
    }

    /**
     * 跑一次 {@code docker version --format {{.Server.Version}}} 并拿回服务端版本；拿不到返回空串。
     *
     * <p>先读完输出再 {@code waitFor}：管道写满而没人读时子进程会卡住不退出（这个坑在真容器用例里已经踩过）。
     */
    private static String dockerServerVersion() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return output;
        } catch (Exception e) {
            // 命令不存在、没有权限、中断……对自检来说都是同一件事：没有可用的 Docker。
            log.debug("docker version 自检失败：{}", e.toString());
            return "";
        }
    }
}
