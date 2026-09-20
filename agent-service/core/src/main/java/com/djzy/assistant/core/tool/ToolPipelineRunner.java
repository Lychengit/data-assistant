package com.djzy.assistant.core.tool;

import com.djzy.assistant.common.pipeline.PostOutcome;
import com.djzy.assistant.common.pipeline.PreOutcome;
import com.djzy.assistant.common.pipeline.ToolExecution;
import com.djzy.assistant.common.pipeline.ToolGuard;
import com.djzy.assistant.common.pipeline.ToolInvocationContext;
import com.djzy.assistant.common.pipeline.ToolPostHook;
import com.djzy.assistant.common.pipeline.ToolPreHook;
import com.djzy.assistant.common.pipeline.ToolResultObserver;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvocationStatus;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行管线唯一引擎（§9.5 / ADR-29）：PRE → GUARD → EXECUTE → POST → RESULT。
 *
 * <p>硬约束（不得被任何调用方绕过，也不得在技能服务 / 接口服务另立特例）：
 * <ul>
 *   <li>GUARD 段**只能收紧、不可放行**；
 *   <li>POST 段**不把失败改成成功**、不改本次调用身份；
 *   <li>RESULT 段**只读**，观察者异常不得影响主流程；
 *   <li>技能与沙箱等 external execution 必须经**通道适配器**映射进同一条管线。
 * </ul>
 */
public final class ToolPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolPipelineRunner.class);

    private final ToolExecution execution;
    private final List<ToolPreHook> preHooks;
    private final List<ToolGuard> guards;
    private final List<ToolPostHook> postHooks;
    private final List<ToolResultObserver> observers;

    public ToolPipelineRunner(
            ToolExecution execution,
            List<ToolPreHook> preHooks,
            List<ToolGuard> guards,
            List<ToolPostHook> postHooks,
            List<ToolResultObserver> observers) {
        this.execution = java.util.Objects.requireNonNull(execution, "execution");
        this.preHooks = preHooks == null ? List.of() : List.copyOf(preHooks);
        this.guards = guards == null ? List.of() : List.copyOf(guards);
        this.postHooks = postHooks == null ? List.of() : List.copyOf(postHooks);
        this.observers = observers == null ? List.of() : List.copyOf(observers);
    }

    public ToolInvocationResult run(ToolInvocationContext context) {
        long startedAt = System.currentTimeMillis();
        context.attributes().put("stage.pipelineStart", startedAt);

        ToolInvocationResult result = runPreAndGuard(context);
        if (result == null) {
            result = runExecute(context);
        }
        result = runPost(context, result);
        context.attributes().put("stage.pipelineEnd", System.currentTimeMillis());
        context.attributes().put("stage.elapsedMs", System.currentTimeMillis() - startedAt);
        observe(context, result);
        return result;
    }

    private ToolInvocationResult runPreAndGuard(ToolInvocationContext context) {
        for (ToolPreHook hook : preHooks) {
            PreOutcome outcome = hook.apply(context);
            if (outcome == null) {
                continue;
            }
            if (outcome.denied()) {
                return ToolInvocationResult.denied(
                        com.djzy.assistant.common.error.UnifiedErrors.FORBIDDEN, outcome.reason());
            }
            if (outcome.asking()) {
                return ToolInvocationResult.awaitingConfirm(outcome.confirmId(), outcome.confirmSummary());
            }
            outcome.rewrittenIfPresent().ifPresent(context::rewriteArguments);
        }
        for (ToolGuard guard : guards) {
            Optional<String> denial = guard.check(context);
            if (denial.isPresent()) {
                return ToolInvocationResult.denied(
                        com.djzy.assistant.common.error.UnifiedErrors.FORBIDDEN, denial.get());
            }
        }
        return null;
    }

    private ToolInvocationResult runExecute(ToolInvocationContext context) {
        if (context.expired()) {
            return ToolInvocationResult.timeout("工具调用超出剩余预算（§9.1 超时预算链）");
        }
        long startedAt = System.currentTimeMillis();
        try {
            ToolInvocationResult result = execution.execute(context);
            context.attributes().put("execute.elapsedMs", System.currentTimeMillis() - startedAt);
            return result == null ? ToolInvocationResult.error(AgentErrorCode.INTERNAL, "工具返回空结果") : result;
        } catch (Exception e) {
            context.attributes().put("execute.elapsedMs", System.currentTimeMillis() - startedAt);
            context.attributes().put("execute.error", e.getClass().getName());
            log.warn("工具执行失败：tool={} traceId={}", context.toolName(), context.traceId(), e);
            return ToolInvocationResult.error(AgentErrorCode.TOOL_FAILED, e.getMessage());
        }
    }

    private ToolInvocationResult runPost(ToolInvocationContext context, ToolInvocationResult original) {
        ToolInvocationResult current = original;
        for (ToolPostHook hook : postHooks) {
            PostOutcome outcome = hook.apply(context, current);
            if (outcome == null) {
                continue;
            }
            switch (outcome.kind()) {
                case ACCEPT -> {
                    return current;
                }
                case REJECT -> {
                    return ToolInvocationResult.denied(
                            com.djzy.assistant.common.error.UnifiedErrors.FORBIDDEN, outcome.reason());
                }
                case REPLACE -> {
                    ToolInvocationResult replacement = outcome.replacement();
                    if (replacement == null) {
                        continue;
                    }
                    if (!current.isSuccess() && replacement.isSuccess()) {
                        log.error("POST 段违规：试图把失败改成成功，已忽略替换。tool={}", context.toolName());
                        continue;
                    }
                    current = replacement;
                }
                case APPEND_NOTE -> {
                    String note = outcome.appendedNote();
                    if (note != null && !note.isBlank()) {
                        current = appendNote(current, note);
                    }
                }
            }
        }
        return current;
    }

    private static ToolInvocationResult appendNote(ToolInvocationResult result, String note) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>(result.meta());
        meta.put("appendedNote", note);
        String content = result.content().isBlank() ? note : result.content() + "\n" + note;
        return new ToolInvocationResult(
                result.status(), content, result.data(), result.errorCode(), result.errorMessage(), meta);
    }

    private void observe(ToolInvocationContext context, ToolInvocationResult result) {
        for (ToolResultObserver observer : observers) {
            try {
                observer.observe(context, result);
            } catch (RuntimeException e) {
                log.warn("RESULT 段观察者异常（已忽略，不影响主流程）：tool={}", context.toolName(), e);
            }
        }
    }

    /** 结果是冻结后的只读对象：RESULT 段不得修改（§9.5）。 */
    public static boolean isFrozen(ToolInvocationResult result) {
        return result != null && result.status() != null;
    }

    public static boolean isTerminal(ToolInvocationResult result) {
        return result.status() == ToolInvocationStatus.OK
                || result.status() == ToolInvocationStatus.DENIED
                || result.status() == ToolInvocationStatus.ERROR;
    }
}
