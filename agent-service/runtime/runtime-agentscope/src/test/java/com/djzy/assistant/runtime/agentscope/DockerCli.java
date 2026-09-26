package com.djzy.assistant.runtime.agentscope;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跑本机 {@code docker} 命令的小工具（**只在测试里用**）。
 *
 * <p>为什么不直接在用例里 {@code new ProcessBuilder(...)}：真容器的用例有两处都要做同一件事——
 * 「先问一句本机有没有可用的 Docker，没有就跳过」「跑完检查有没有留下容器」。抄两遍就会漂，
 * 而「跳过」这件事一旦漂了，最坏的结果是**用例默默不跑了却显示全绿**。
 *
 * <p>刻意只做三件事，不做「通用的进程执行框架」：Docker 的调用点在测试里就这几处，
 * 抽太多反而要看半天才知道用例在验什么。
 */
final class DockerCli {

    private static final Logger log = LoggerFactory.getLogger(DockerCli.class);

    /** 单次命令的超时：够慢机器上的 docker CLI 回话，又不至于让用例一直挂着。 */
    private static final int TIMEOUT_SECONDS = 60;

    private DockerCli() {}

    /** 本机有没有可用的 Docker：**跑得通** {@code docker version} 才算（有 CLI 但引擎没起来不算）。 */
    static boolean available() {
        try {
            return !run("docker", "version", "--format", "{{.Server.Version}}").isBlank();
        } catch (Exception e) {
            log.debug("docker version 跑不通（按「没有 Docker」处理）：{}", e.toString());
            return false;
        }
    }

    /**
     * 有没有留下本用例会建的那种容器（按镜像找，{@code ubuntu:22.04} 就是测试专用镜像）。
     *
     * <p>它不是「Docker 里一共有几个容器」——机器上别人留的容器不该让用例失败，
     * 所以只认祖先镜像是测试镜像的那些。
     */
    static boolean leftoverTestContainers() {
        try {
            return !run("docker", "ps", "-a", "--filter", "ancestor=ubuntu:22.04", "--format", "{{.ID}}")
                    .isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 跑一条命令并拿回它的合并输出（标准输出 + 标准错误）；超时或跑不起来返回空串。
     *
     * <p>要**先读完输出再 waitFor**：管道写满而没人读时，子进程会卡住不退出。
     */
    static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "";
        }
        return output;
    }
}
