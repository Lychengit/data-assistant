package com.djzy.assistant.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.pipeline.PostOutcome;
import com.djzy.assistant.common.pipeline.PreOutcome;
import com.djzy.assistant.common.pipeline.ToolDescriptor;
import com.djzy.assistant.common.pipeline.ToolGuard;
import com.djzy.assistant.common.pipeline.ToolInvocationContext;
import com.djzy.assistant.common.pipeline.ToolPostHook;
import com.djzy.assistant.common.pipeline.ToolPreHook;
import com.djzy.assistant.common.pipeline.ToolResultObserver;
import com.djzy.assistant.core.tool.guard.InMemoryQuotaStores;
import com.djzy.assistant.core.tool.guard.WriteQuotaGuard;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvocationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** §9.5 / ADR-29：GUARD 只能收紧；POST 不能把失败改成成功；RESULT 只读。 */
class ToolPipelineRunnerTest {

    @Test
    void guardDenialWinsAndExecutionNeverRuns() {
        AtomicInteger executions = new AtomicInteger();
        ToolPipelineRunner runner = runner(
                ctx -> {
                    executions.incrementAndGet();
                    return ToolInvocationResult.ok("ok", Map.of());
                },
                List.of(),
                List.of(context -> Optional.of("WRITE_QUOTA_EXCEEDED")),
                List.of(),
                List.of());

        ToolInvocationResult result = runner.run(context(SideEffect.WRITE));

        assertEquals(ToolInvocationStatus.DENIED, result.status());
        assertEquals(0, executions.get(), "GUARD 拒绝后不得执行");
    }

    @Test
    void preAskSuspendsWithoutExecuting() {
        AtomicInteger executions = new AtomicInteger();
        ToolPipelineRunner runner = runner(
                ctx -> {
                    executions.incrementAndGet();
                    return ToolInvocationResult.ok("ok", Map.of());
                },
                List.of(ctx -> PreOutcome.ask("c-1", "确认上传？")),
                List.of(),
                List.of(),
                List.of());

        ToolInvocationResult result = runner.run(context(SideEffect.WRITE));

        assertEquals(ToolInvocationStatus.AWAITING_CONFIRM, result.status());
        assertEquals("c-1", result.meta().get("confirmId"));
        assertEquals(0, executions.get());
    }

    @Test
    void preHookCanRewriteArguments() {
        List<Map<String, Object>> seen = new ArrayList<>();
        ToolPipelineRunner runner = runner(
                ctx -> {
                    seen.add(ctx.arguments());
                    return ToolInvocationResult.ok("ok", Map.of());
                },
                List.of(ctx -> PreOutcome.rewrite(Map.of("month", "2026-08"))),
                List.of(),
                List.of(),
                List.of());

        runner.run(context(SideEffect.READ));

        assertEquals("2026-08", seen.get(0).get("month"));
    }

    @Test
    void postHookCanReplaceSuccessfulResult() {
        ToolPipelineRunner runner = runner(
                ctx -> ToolInvocationResult.ok("原始大结果", Map.of()),
                List.of(),
                List.of(),
                List.of((ctx, result) -> PostOutcome.replace(ToolInvocationResult.ok("已 spill，locator=art-1", Map.of()))),
                List.of());

        ToolInvocationResult result = runner.run(context(SideEffect.READ));

        assertEquals("已 spill，locator=art-1", result.content());
    }

    @Test
    void postHookCannotTurnFailureIntoSuccess() {
        ToolPipelineRunner runner = runner(
                ctx -> ToolInvocationResult.error(AgentErrorCode.TOOL_FAILED, "下游失败"),
                List.of(),
                List.of(),
                List.of((ctx, result) -> PostOutcome.replace(ToolInvocationResult.ok("假装成功", Map.of()))),
                List.of());

        ToolInvocationResult result = runner.run(context(SideEffect.READ));

        assertEquals(ToolInvocationStatus.ERROR, result.status());
        assertFalse(result.isSuccess());
    }

    @Test
    void observerFailureDoesNotBreakMainFlow() {
        ToolResultObserver broken = (ctx, result) -> {
            throw new IllegalStateException("观测系统故障");
        };
        ToolPipelineRunner runner = runner(
                ctx -> ToolInvocationResult.ok("ok", Map.of()), List.of(), List.of(), List.of(), List.of(broken));

        ToolInvocationResult result = runner.run(context(SideEffect.READ));

        assertEquals(ToolInvocationStatus.OK, result.status());
    }

    @Test
    void expiredDeadlineBecomesTimeout() {
        ToolPipelineRunner runner = runner(
                ctx -> ToolInvocationResult.ok("ok", Map.of()), List.of(), List.of(), List.of(), List.of());
        ToolInvocationContext expired = new ToolInvocationContext(
                "iface_x", Map.of(), "u1", "s1", "t1", "trace1", "r1", SideEffect.READ, System.currentTimeMillis() - 1);

        assertEquals(ToolInvocationStatus.TIMEOUT, runner.run(expired).status());
    }

    @Test
    void writeQuotaGuardAllowsThreeWritesThenDenies() {
        WriteQuotaGuard guard = new WriteQuotaGuard(new InMemoryQuotaStores(), 3);
        ToolInvocationContext ctx = context(SideEffect.WRITE);

        assertTrue(guard.check(ctx).isEmpty());
        assertTrue(guard.check(ctx).isEmpty());
        assertTrue(guard.check(ctx).isEmpty());
        assertTrue(guard.check(ctx).isPresent(), "第 4 次写操作必须被 GUARD 拒绝（§19.9 ≤3 次）");
    }

    private static ToolPipelineRunner runner(
            com.djzy.assistant.common.pipeline.ToolExecution execution,
            List<ToolPreHook> preHooks,
            List<ToolGuard> guards,
            List<ToolPostHook> postHooks,
            List<ToolResultObserver> observers) {
        return new ToolPipelineRunner(execution, preHooks, guards, postHooks, observers);
    }

    private static ToolInvocationContext context(SideEffect sideEffect) {
        return new ToolInvocationContext(
                "iface_profit",
                Map.of("month", "2026-07"),
                "u1",
                "s1",
                "t1",
                "trace1",
                "r1",
                sideEffect,
                System.currentTimeMillis() + 10_000);
    }

    @Test
    void descriptorDefaultsMatchSpec() {
        assertEquals(SideEffect.WRITE, ToolDescriptor.write("iface_upload", ToolCategory.IFACE).sideEffect());
        assertEquals(3, ToolDescriptor.write("iface_upload", ToolCategory.IFACE).writeQuotaPerTurn());
    }
}
