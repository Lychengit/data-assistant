package com.djzy.assistant.runtime.agentscope;

import com.djzy.assistant.spi.AgentRunRequest;
import io.agentscope.core.model.Model;

/**
 * 模型工厂端口：把「用哪个模型」留给部署配置（OpenAI 兼容 / DeepSeek / DashScope 等），
 * 适配器本身不绑定供应商。
 */
@FunctionalInterface
public interface ModelProvider {

    Model modelFor(AgentRunRequest request);

    /**
     * 这个请求要用哪个模型——返回一个**能当缓存键用的身份串**（T1-06）。
     *
     * <p>为什么需要它：模型是 agent 级的字段，所以共享 agent 必须按「模型身份」分桶；
     * 但 {@code Model} 对象本身没有稳定的 id，各家实现都不一样。这里只要一个「同样的配置 → 同样的串、
     * 换了配置 → 换了串」的标识，具体怎么拼由实现决定（例如「供应商 + 适配器 + 模型名 + 是否要推理原文」）。
     *
     * <p>默认返回 {@code "default"}：测试替身与单模型部署不用关心这件事。
     */
    default String modelKeyFor(AgentRunRequest request) {
        return "default";
    }
}
