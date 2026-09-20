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
}
