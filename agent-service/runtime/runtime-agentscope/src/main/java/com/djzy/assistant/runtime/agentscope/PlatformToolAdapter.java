package com.djzy.assistant.runtime.agentscope;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 框架工具 → 平台 {@code ToolInvoker} 的桥（ADR-32 ③ / §18.11.3）。
 *
 * <p>这是「运行时不得自行执行工具」的落点：框架侧只看到工具 schema，
 * 真正执行一律回到平台管线（PRE → GUARD → EXECUTE → POST → RESULT）。
 */
final class PlatformToolAdapter implements AgentTool {

    private final ToolSpec spec;
    private final AgentRunRequest request;
    private final String requestId;

    PlatformToolAdapter(ToolSpec spec, AgentRunRequest request, String requestId) {
        this.spec = spec;
        this.request = request;
        this.requestId = requestId;
    }

    @Override
    public String getName() {
        return spec.name();
    }

    @Override
    public String getDescription() {
        return spec.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return spec.inputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return !spec.sideEffect().isWrite();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        ToolUseBlock toolUse = param.getToolUseBlock();
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (context != null && context.getExtra() != null) {
            attributes.putAll(context.getExtra());
        }
        ToolInvocationRequest invocation = new ToolInvocationRequest(
                spec.name(),
                param.getInput() == null ? Map.of() : param.getInput(),
                context == null ? request.userId() : context.getUserId(),
                context == null ? request.sessionId() : context.getSessionId(),
                stringOf(attributes.get("turnId")),
                stringOf(attributes.get("traceId")),
                requestId,
                toolUse == null ? null : stringOf(attributes.get("confirmId")),
                request.deadlineEpochMs(),
                attributes);
        return Mono.defer(() -> Mono.from(request.toolInvoker().invoke(invocation)))
                .map(result -> toBlock(toolUse, result))
                // 错误归一化：对外只给稳定措辞，不泄露内部堆栈与异常细节（TCK-12）。
                .onErrorResume(e -> Mono.just(ToolResultBlock.error("工具暂不可用，请稍后重试")));
    }

    /**
     * 工具结果 → 框架结果块。
     *
     * <p>为什么必须显式带 state：框架的默认块是「成功」。失败结果若也标成成功，
     * 工具卡会出现「状态成功、正文却是失败原因」这种自相矛盾的呈现，审计也读不准。
     * 状态只做平台侧 {@code ToolInvocationStatus} 的直译，不猜框架语义。
     */
    private static ToolResultBlock toBlock(ToolUseBlock toolUse, ToolInvocationResult result) {
        String id = toolUse == null ? null : toolUse.getId();
        String name = toolUse == null ? null : toolUse.getName();
        String content = result.content();
        String text = content == null || content.isBlank() ? "工具暂不可用，请稍后重试" : content;
        ToolResultState state = switch (result.status()) {
            case DENIED -> ToolResultState.DENIED;
            case ERROR, TIMEOUT -> ToolResultState.ERROR;
            default -> ToolResultState.SUCCESS;
        };
        return new ToolResultBlock(
                id, name, List.<ContentBlock>of(TextBlock.builder().text(text).build()), metadata(text), state);
    }

    /**
     * 结果块元数据。
     *
     * <p>为什么把正文和字节数塞进 metadata：框架的 {@code TOOL_RESULT_END} 事件不带结果体，
     * 而事实表要记 {@code result_size}、界面要显示「结果 N 字节」（§8.3 / §12.2）。
     * 平台侧正好知道这次返回了多少字节，从这里带出去比事后估算准。
     */
    private static Map<String, Object> metadata(String text) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("content", text);
        metadata.put("resultSize", text.getBytes(StandardCharsets.UTF_8).length);
        return metadata;
    }
    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
