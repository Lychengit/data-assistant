package com.djzy.assistant.core.tool.guard;

import com.djzy.assistant.common.pipeline.ToolGuard;
import com.djzy.assistant.common.pipeline.ToolInvocationContext;
import java.util.Optional;

/**
 * GUARD 段：写操作次数上限（§19.9 的 ≤3 次，默认 3，可配置）。
 *
 * <p>写在 GUARD 而不是 PRE：配额必须**只能收紧、不能被前面的放行抵消**（§9.5）。
 */
public final class WriteQuotaGuard implements ToolGuard {

    private final WriteQuotaStore store;
    private final int quotaPerTurn;

    public WriteQuotaGuard(WriteQuotaStore store, int quotaPerTurn) {
        this.store = store;
        this.quotaPerTurn = quotaPerTurn;
    }

    @Override
    public Optional<String> check(ToolInvocationContext context) {
        if (!context.sideEffect().isWrite()) {
            return Optional.empty();
        }
        if (context.turnId() == null) {
            return Optional.of("WRITE_QUOTA_NO_TURN");
        }
        int used = store.incrementAndGet(context.userId(), context.turnId());
        if (used > quotaPerTurn) {
            return Optional.of("WRITE_QUOTA_EXCEEDED:" + used + "/" + quotaPerTurn);
        }
        return Optional.empty();
    }
}
