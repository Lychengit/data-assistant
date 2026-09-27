package com.djzy.assistant.runtime.agentscope;

import java.util.Optional;

/**
 * 「按路径读沙箱产物」的口子（§18.5.3：沙箱产物由**宿主代理**读出来、再经网关上传）。
 *
 * <p><b>为什么不能让模型来搬字节</b>：按老路子，产物要在沙箱里编成 base64、打进工具结果、
 * 由模型原样抄进上传调用的参数里。这条路 2026-09-27 实测翻车——模型自己打印了 base64 的
 * **截断预览**，然后拿预览加头尾碎片「拼」出一份 224 字节的坏 xlsx（真文件 6379 字节），
 * 上传成功、链接有效、文件打不开。8.5KB 的不透明字符串本来就不该经过语言模型。
 *
 * <p>所以宿主代理（agent-service）直接把文件读出来填进上传参数，模型只给一个**路径**。
 * 这也是"沙箱里没有凭据、出网只发生在宿主代理"（ADR-34）的自然延伸：能读沙箱的是宿主，
 * 该由它来读。
 *
 * <p>读不到返回空——由调用方决定怎么报，绝不回一个空字节数组冒充"读到了"。
 */
@FunctionalInterface
interface SandboxArtifactReader {

    Optional<byte[]> read(String path);
}