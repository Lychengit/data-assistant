package com.djzy.assistant.core.runtime;

import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.RuntimeMismatchException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * agent 运行时选择与准入（§18.11.6 / §19.14）。
 *
 * <p>规则：
 * <ul>
 *   <li>换实现只换 {@code runtime-<vendor>} module + 一行配置 {@code agent.runtime=<id>}；
 *   <li>新实现必须先通过契约回归（TCK）才允许上线；
 *   <li>初始化失败 → 回退到**上一个已通过 TCK 的实现**（宁可退回旧实现，也不放行未验证实现）；
 *   <li>会话记录绑定 {@code runtimeId}，不一致即拒绝恢复（不「假装恢复成功」）。
 * </ul>
 */
public final class RuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRegistry.class);

    private final Map<String, AgentRuntimePort> runtimes;
    private final List<String> tckPassedOrder;
    private final String configuredId;

    public RuntimeRegistry(List<AgentRuntimePort> available, List<String> tckPassedOrder, String configuredId) {
        Map<String, AgentRuntimePort> map = new LinkedHashMap<>();
        available.forEach(r -> map.put(r.id(), r));
        this.runtimes = Map.copyOf(map);
        this.tckPassedOrder = List.copyOf(tckPassedOrder);
        this.configuredId = configuredId;
    }

    /** 当前生效实现（含 fail-closed 回退）。 */
    public AgentRuntimePort active() {
        AgentRuntimePort configured = runtimes.get(configuredId);
        if (configured != null && tckPassedOrder.contains(configuredId)) {
            return configured;
        }
        Optional<AgentRuntimePort> fallback = tckPassedOrder.stream()
                .filter(runtimes::containsKey)
                .reduce((first, second) -> second)
                .map(runtimes::get);
        if (fallback.isPresent()) {
            log.error(
                    "运行时 {} 不可用或未通过 TCK，回退到上一个已通过 TCK 的实现 {}",
                    configuredId,
                    fallback.get().id());
            return fallback.get();
        }
        throw new IllegalStateException("没有可用且已通过 TCK 的 agent 运行时，拒绝启动（fail-closed）");
    }

    public Optional<AgentRuntimePort> byId(String runtimeId) {
        return Optional.ofNullable(runtimes.get(runtimeId));
    }

    /** 恢复前校验（§19.14）：不一致即拒绝恢复，老会话转只读。 */
    public void assertResumable(String sessionRuntimeId) {
        String activeId = active().id();
        if (sessionRuntimeId != null && !sessionRuntimeId.equals(activeId)) {
            throw new RuntimeMismatchException("(session)", activeId, sessionRuntimeId);
        }
    }

    public boolean isTckPassed(String runtimeId) {
        return tckPassedOrder.contains(runtimeId);
    }
}
