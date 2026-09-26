package com.djzy.assistant.runtime.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 把「这一轮的系统提示词」按轮塞进去（ADR-30 / §6.6）。
 *
 * <p>**为什么不用 {@code builder.sysPrompt(...)}**：系统提示词里写着「今天 / 本月 / 本季度」
 * 和**这一轮的接口清单**，也就是每轮都不一样、跨天必变。烤进 agent 实例有三个后果：
 * ① 这个 agent 只能服务一次调用，共享实例（T1-06）就没意义了；
 * ② 跨天之后必须重建，否则模型还在按昨天的日期回答「今天」；
 * ③ 想「今天这一轮用今天的值」，就得让 agent 的生命周期跟日历挂钩，纯属自找麻烦。
 *
 * <p>框架正好留了口子：中间件的 {@code onSystemPrompt} 在**每次调用**开始时被问一次，
 * 而且拿得到这一轮的 {@link RuntimeContext}。于是提示词跟调用走，agent 本身与日期无关。
 *
 * <p>提示词为空时**不动**框架给的基线（{@code currentPrompt}）：这样「这一轮没给提示词」
 * 与「把别人的提示词抹掉」不会混为一谈。
 */
final class PerCallSystemPrompt implements MiddlewareBase {

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            return Mono.just(currentPrompt);
        }
        Object prompt = ctx.get(CallAttributes.SYSTEM_PROMPT);
        if (prompt == null || String.valueOf(prompt).isBlank()) {
            return Mono.just(currentPrompt);
        }
        return Mono.just(String.valueOf(prompt));
    }
}
