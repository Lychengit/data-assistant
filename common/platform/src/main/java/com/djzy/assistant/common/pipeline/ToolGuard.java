package com.djzy.assistant.common.pipeline;

import java.util.Optional;

/**
 * GUARD 段守卫：**只能拒绝或弃权，没有放行的权力**（§9.5）。
 *
 * <p>配额收紧、越权收紧、重复调用熔断、写次数上限（§19.9 的 ≤3 次）一律写这里，不写 PRE。
 */
public interface ToolGuard {

    /** @return 拒绝原因；{@link Optional#empty()} 表示弃权（不作判断）。 */
    Optional<String> check(ToolInvocationContext context);
}
