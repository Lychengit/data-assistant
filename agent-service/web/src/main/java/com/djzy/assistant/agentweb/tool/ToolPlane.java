package com.djzy.assistant.agentweb.tool;

import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolInvoker;
import java.util.List;

/**
 * 工具面（§4.8 节点 A + §19.7 节点 E）：**看得见什么**与**怎么调**都只由网关给。
 *
 * <p>两个方法都要求每轮重新取：权限变更 ≤15 分钟延迟生效（§19.4），而骨架期本就零缓存直查 PG（§0.3-4），
 * 所以我们不给自己留「缓存里还看得见、实际已被撤权」的窗口。
 */
public interface ToolPlane {

    /** 节点 A：按用户凭证取可见工具清单（拿不到范围明细，§4.3）。 */
    ToolCatalog catalogFor(String bearerToken);

    /**
     * 这一轮该用户**可见的技能编码**（H-06a）：技能内容由平台下发进工作区，运行时只管用。
     *
     * <p>默认空列表：技能没开的时候（{@code agent-service.workspace-skills=none}）谁都不用管它。
     * 与 {@link #catalogFor} 同源（都来自网关的能力清单），所以两者看到的授权是同一份快照口径。
     */
    default List<String> skillsFor(String bearerToken) {
        return List.of();
    }

    /** 节点 E 的调用端：每次调用都带用户凭证走网关，网关单点判定（§19.7）。 */
    ToolInvoker invokerFor(String bearerToken);

    /** 未接数据面的骨架形态：清单为空，运行时调不到任何工具（不是「放行」而是「无工具」）。 */
    static ToolPlane empty() {
        return new ToolPlane() {
            @Override
            public ToolCatalog catalogFor(String bearerToken) {
                return ToolCatalog.empty();
            }

            @Override
            public ToolInvoker invokerFor(String bearerToken) {
                return request -> reactor.core.publisher.Mono.fromSupplier(() ->
                        com.djzy.assistant.spi.tool.ToolInvocationResult.denied(
                                com.djzy.assistant.common.error.UnifiedErrors.FORBIDDEN, "TOOL_PLANE_NOT_CONFIGURED"));
            }
        };
    }
}
