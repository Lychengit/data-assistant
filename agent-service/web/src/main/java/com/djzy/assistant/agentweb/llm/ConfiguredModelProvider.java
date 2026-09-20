package com.djzy.assistant.agentweb.llm;

import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.llm.LlmAdapter;
import com.djzy.assistant.common.llm.LlmProviderConfig;
import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
import com.djzy.assistant.runtime.agentscope.ModelProvider;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.util.function.BooleanSupplier;

/**
 * 按库里的生效配置构造模型客户端（ADR-14：OpenAI 兼容协议，默认 DeepSeek）。
 *
 * <p>三个刻意的设计选择：
 *
 * <ol>
 *   <li><b>每轮直查，不缓存</b>：管理端一启用新供应商，下一轮就生效。
 *       这跟 §7 里 M2「改动立即生效」是同一条口径——配置读取的代价远小于一次模型调用。
 *   <li><b>没有生效配置就抛，不回退</b>：绝不「找不到就用环境变量里的模型」，
 *       那种兜底会让「没配」和「配错了」长得一模一样。失败信息直接说明去哪儿配。
 *   <li><b>明文 Key 只在本方法内短暂存在</b>：交给 ModelCreationContext 之后就不再被引用，
 *       不进日志、不进异常消息、不进模型上下文（§20.1.6 硬约束第 7 条）。
 * </ol>
 */
public final class ConfiguredModelProvider implements ModelProvider {

    /** 请求属性名：本轮是否要模型返回推理原文（§20.6 的「推理原文」档）。 */
    public static final String ATTR_ENABLE_THINKING = "enableThinking";

    private final JdbcLlmProviderStore store;
    private final BooleanSupplier defaultEnableThinking;

    public ConfiguredModelProvider(JdbcLlmProviderStore store, BooleanSupplier defaultEnableThinking) {
        this.store = store;
        this.defaultEnableThinking = defaultEnableThinking;
    }

    @Override
    public Model modelFor(AgentRunRequest request) {
        LlmProviderConfig config = store.findEnabled().orElseThrow(NoModelConfiguredException::new);
        LlmAdapter adapter = LlmAdapter.byId(config.adapter())
                .orElseThrow(() -> new IllegalStateException(
                        "生效的供应商 " + config.providerId() + " 使用了当前版本不认识的适配器：" + config.adapter()));

        ModelCreationContext context = ModelCreationContext.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .stream(true)
                .enableThinking(wantsReasoning(request))
                .build();
        return ModelRegistry.resolve(adapter.modelRef(config.model()), context);
    }

    /** 逐轮取请求属性，缺省回退到平台级设置（个人设置项落地前，先由全局配置兜着）。 */
    private boolean wantsReasoning(AgentRunRequest request) {
        Object attribute = request == null ? null : request.attributes().get(ATTR_ENABLE_THINKING);
        if (attribute instanceof Boolean flag) {
            return flag;
        }
        return defaultEnableThinking.getAsBoolean();
    }

    /** 没有任何生效配置——这是**合法状态**（平台可以只有 noop 运行时），但没法回答需要模型的问题。 */
    public static final class NoModelConfiguredException extends RuntimeMisconfiguredException {

        NoModelConfiguredException() {
            super(UnifiedErrors.LLM_NOT_CONFIGURED);
        }
    }
}
