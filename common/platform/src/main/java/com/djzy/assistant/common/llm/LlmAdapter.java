package com.djzy.assistant.common.llm;

import java.util.List;
import java.util.Optional;

/**
 * 可用的模型适配器白名单（ADR-14：「OpenAI 兼容协议抽象，默认 DeepSeek，可切换」）。
 *
 * <p>这里的每个 {@code id} 都与 AgentScope OpenAI 扩展注册的 {@code ModelProvider.providerId()}
 * **逐一对应**，并且是模型引用串的前缀（{@code deepseek:deepseek-flash}）。
 * 白名单放在 common 而不是某一侧：管理端要靠它做**写入期**校验（不认识的适配器直接 400，
 * 而不是等运行时才发现解析不了），agent-service 要靠它把 adapter 映射成模型引用串。
 *
 * <p>「接其它模型」= 在这里加一个枚举值。加之前先确认扩展包里确实注册了同名 provider，
 * 否则会出现「界面上能选、运行时解析不了」的错位。
 *
 * <p><b>模型名是供应商侧的契约，会变。</b>2026-09 实测：DeepSeek 现役模型只有
 * {@code deepseek-flash}（DeepSeek-V4.1-Flash，快 / 便宜，支持图像理解）与
 * {@code deepseek-v4-pro}（DeepSeek-V4-Pro-0813，更强更贵），
 * 早期文档里的 {@code deepseek-chat} / {@code deepseek-reasoner} 已从价格表下线。
 * 所以这里不再只给一个字符串，而是给 {@link #knownModels}：管理端把它渲染成候选下拉，
 * 运维照抄，避免又抄到一个已经下线的名字（详见 https://api-docs.deepseek.com/zh-cn/quick_start/pricing ）。
 */
public enum LlmAdapter {

    /** 默认选型（ADR-14）：现役最便宜的一档。 */
    DEEPSEEK(
            "deepseek",
            "https://api.deepseek.com",
            "deepseek-flash",
            List.of("deepseek-flash", "deepseek-v4-pro")),

    /** 以下四个扩展包里已注册同名 provider，骨架期先列出、默认端点留空由部署方填。 */
    GLM("glm", null, null, List.of()),
    KIMI("kimi", null, null, List.of()),
    MINIMAX("minimax", null, null, List.of()),
    OPENAI("openai", null, null, List.of());

    private final String id;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final List<String> knownModels;

    LlmAdapter(String id, String defaultBaseUrl, String defaultModel, List<String> knownModels) {
        this.id = id;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.knownModels = List.copyOf(knownModels);
    }

    /** 适配器 id，同时是模型引用串的前缀。 */
    public String id() {
        return id;
    }

    /** 已知默认端点；为 null 表示必须由部署方填。 */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** 界面预填用的默认模型名；为 null 表示必须由部署方填。 */
    public String defaultModel() {
        return defaultModel;
    }

    /** 现役模型名（照抄即可用）；为空表示该供应商的模型名由部署方自行确定。 */
    public List<String> knownModels() {
        return knownModels;
    }

    /**
     * 拼出运行时用的模型引用串（{@code deepseek:deepseek-flash}）。
     *
     * <p>前缀不能省：AgentScope 的 provider 靠它挑适配器，裸模型名会解析不到。
     */
    public String modelRef(String model) {
        return id + ":" + model;
    }

    public static Optional<LlmAdapter> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (LlmAdapter adapter : values()) {
            if (adapter.id.equals(normalized)) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
    }
}