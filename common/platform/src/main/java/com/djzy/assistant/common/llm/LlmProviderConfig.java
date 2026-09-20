package com.djzy.assistant.common.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 供应商配置（ADR-14）：**用哪个适配器、哪个模型、哪把 Key**。
 *
 * <p>{@code apiKey} 是解密后的**明文**，只在进程内存里短暂存在，用于构造模型客户端；
 * 它**永远不进审计、不进日志、不进界面**（§20.1.6 硬约束第 7 条）。
 * 对外可见的那一份由 {@link #toView()} 给出，只带 {@code keyHint}。
 *
 * @param providerId 逻辑名（主键），如 {@code deepseek}
 * @param adapter 适配器类型，映射到运行时的 ModelProvider，如 {@code deepseek}
 * @param baseUrl OpenAI 兼容端点
 * @param model 模型名，如 {@code deepseek-flash}
 * @param apiKey 明文密钥（仅内存）
 * @param keyHint 回显提示（{@code sk-****1234}），不含明文
 * @param enabled 是否生效（同一时刻至多一个）
 */
public record LlmProviderConfig(
        String providerId,
        String adapter,
        String baseUrl,
        String model,
        String apiKey,
        String keyHint,
        boolean enabled) {

    /**
     * 对外视图（界面 / 接口响应 / 审计快照共用）：**不含明文 Key**。
     *
     * <p>刻意做成一个方法而不是让调用方自己拼字段——密钥这类东西，
     * 「谁能被输出」应当是类型自带的属性，而不是每个调用点各自记得遮一下。
     */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("providerId", providerId);
        view.put("adapter", adapter);
        view.put("baseUrl", baseUrl);
        view.put("model", model);
        view.put("keyHint", keyHint);
        view.put("enabled", enabled);
        return view;
    }

    /** 审计快照：与对外视图同形（审计要能查「换了哪把 Key」，但不能把 Key 复制进去）。 */
    public Map<String, Object> toAuditMap() {
        return toView();
    }
}
