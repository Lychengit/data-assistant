package com.djzy.assistant.core.sse;

/**
 * 思考流可见性（§20.6，用户个人可设置）。
 *
 * <p>{@link #STEP_CARD_ONLY} 为默认档：只推「做了什么」（工具名 + 参数概要 + 结果概要），不推思考内容。
 * {@link #RAW_REASONING} 推送模型返回的原始推理内容（DeepSeek 的 {@code reasoning_content}）；
 * 明示风险：System Prompt 片段与内部细节可能被看到，视为用户已知悉并接受。
 */
public enum ThinkingVisibility {
    STEP_CARD_ONLY,
    RAW_REASONING
}
