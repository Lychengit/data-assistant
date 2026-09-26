package com.djzy.assistant.agentweb.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 启动期沙箱自检（H-05）：**开了沙箱就必须真的有 Docker**，拿不到就拒绝启动。
 *
 * <p>为什么这条用例值得写：这段代码平时一行都不跑（默认关着），只有运维把开关打开时才生效——
 * 而它生效的那一刻，正是「隔离到底有没有生效」最要紧的一刻。它坏掉的两种方式都很糟：
 * ① 关着的时候还去探测 Docker（给不相关的人加了前置条件，起不来还看不出原因）；
 * ② 开着但拿不到 Docker 时静默放行（脚本以 agent 服务的身份跑在宿主机上，运维却以为在容器里）。
 *
 * <p>所以三条用例正好对着这两件事：关着连探测都不做、开着探测不到就拒绝启动、开着且 Docker 在位就通过。
 * 探测函数是注入进来的（见 {@code StartupSandboxCheck} 的构造器），**不会真的去调 docker**——用例不该
 * 因为跑它的机器上有没有 Docker 而改变结果。
 */
class StartupSandboxCheckTest {

    @Test
    void 沙箱关着时_连探测都不做_没有Docker也照常启动() {
        // 探测函数一被调用就抛错：关着的时候要是调了，用例当场失败。
        // 这比「假装探测成功」更严——它断言的是「关着就一点前置条件都不加」。
        StartupSandboxCheck check = new StartupSandboxCheck(new AgentServiceProperties(), () -> {
            throw new IllegalStateException("沙箱关着的时候不该去探测 Docker");
        });

        assertDoesNotThrow(check::verify, "默认档（关着）不该有任何前置条件");
    }

    @Test
    void 沙箱开着但拿不到Docker_拒绝启动() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new StartupSandboxCheck(开着沙箱(), () -> "").verify(),
                "拿不到 docker version 时必须显式失败，不能悄悄退回本机执行");

        assertTrue(
                failure.getMessage().contains("拒绝启动"),
                "报错要说清楚「宁可起不来，也不要假装隔离了」，实际：" + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("不会退回本机执行"),
                "报错要明确告诉运维「不会偷偷跑在本机」，实际：" + failure.getMessage());
    }

    @Test
    void 沙箱开着且Docker可用_通过() {
        assertDoesNotThrow(
                () -> new StartupSandboxCheck(开着沙箱(), () -> "27.0.3").verify(),
                "Docker 在位（拿得到服务端版本）就该正常通过");
    }

    private static AgentServiceProperties 开着沙箱() {
        AgentServiceProperties properties = new AgentServiceProperties();
        properties.getSandbox().setEnabled(true);
        return properties;
    }
}
