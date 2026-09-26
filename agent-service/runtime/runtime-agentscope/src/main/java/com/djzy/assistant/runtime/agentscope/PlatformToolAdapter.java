package com.djzy.assistant.runtime.agentscope;

import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvoker;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 框架工具 → 平台 {@code ToolInvoker} 的桥（ADR-32 ③ / §18.11.3）。
 *
 * <p>这是「运行时不得自行执行工具」的落点：框架侧只看到工具 schema，
 * 真正执行一律回到平台管线（PRE → GUARD → EXECUTE → POST → RESULT）。
 *
 * <p>**它里面不存任何「这一次调用」的东西**（回调、请求 id、截止时间都不存）：同一个工具面
 * 的 agent 是跨用户、跨会话共享的（T1-06），工具对象在并发调用之间是同一个。
 * 这些值每次都从 {@link RuntimeContext} 的 extra 里取——框架会把发起这一轮时带进来的
 * {@code RuntimeContext} 原样交给工具，所以拿到的永远是**这一轮**的值。
 *
 * <p>**顺带记一笔：为什么权限规则不在这里**。平台的工具可见性靠「注册了哪些工具」实现
 * （工具面 = 用户这一轮能调的接口），不靠框架的 DENY 规则；而框架的
 * {@code PermissionEngine} 只对 {@code ToolBase} 的子类生效，本类是更老的
 * {@code AgentTool} 接口实现，走的是「legacy 直接放行」那条路（见 ReActAgent
 * {@code evaluateOne}）。要改这个结论就得先继承 {@code ToolBase}，
 * 这点写在 TASKS.md 的 T1-12 里，别指望在别处调规则就能拦住它。
 */
final class PlatformToolAdapter implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlatformToolAdapter.class);

    private final ToolSpec spec;

    PlatformToolAdapter(ToolSpec spec) {
        this.spec = spec;
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
        Map<String, Object> attributes = context == null || context.getExtra() == null
                ? Map.of()
                : context.getExtra();
        ToolInvoker invoker =
                attributes.get(CallAttributes.TOOL_INVOKER) instanceof ToolInvoker found ? found : null;
        if (invoker == null) {
            // 正常路径到不了这里：发起这一轮时一定把 invoker 放进了 RuntimeContext。
            // 真到了这里就**一律不执行**——绕开平台管线的工具调用，等于同时绕开配额、
            // 防重放、确认闸口与审计（§18.11.3），宁可失败也不能放过去。
            log.error(
                    "工具调用缺少平台执行回调，已拒绝执行：tool={} turnId={}",
                    spec.name(),
                    attributes.get(CallAttributes.TURN_ID));
            return Mono.just(errorBlock(toolUse, "工具暂不可用，请稍后重试"));
        }
        ToolInvocationRequest invocation = new ToolInvocationRequest(
                spec.name(),
                param.getInput() == null ? Map.of() : param.getInput(),
                context.getUserId(),
                context.getSessionId(),
                stringOf(attributes.get(CallAttributes.TURN_ID)),
                stringOf(attributes.get(CallAttributes.TRACE_ID)),
                stringOf(attributes.get(CallAttributes.REQUEST_ID)),
                stringOf(attributes.get(CallAttributes.CONFIRM_ID)),
                longOf(attributes.get(CallAttributes.DEADLINE_EPOCH_MS)),
                attributes);
        return Mono.defer(() -> Mono.from(invoker.invoke(invocation)))
                .map(result -> toBlock(toolUse, result))
                // 错误归一化：对外只给稳定措辞，不泄露内部堆栈与异常细节（TCK-12）。
                .onErrorResume(e -> Mono.just(errorBlock(toolUse, "工具暂不可用，请稍后重试")));
    }

    /**
     * 工具结果 → 框架结果块。
     *
     * <p>为什么必须显式带 state：框架的默认块是「成功」。失败结果若也标成成功，
     * 工具卡会出现「状态成功、正文却是失败原因」这种自相矛盾的呈现，审计也读不准。
     * 状态只做平台侧 {@code ToolInvocationStatus} 的直译，不猜框架语义。
     */
    private static ToolResultBlock toBlock(ToolUseBlock toolUse, ToolInvocationResult result) {
        String content = result.content();
        String text = content == null || content.isBlank() ? "工具暂不可用，请稍后重试" : content;
        ToolResultState state = switch (result.status()) {
            case DENIED -> ToolResultState.DENIED;
            case ERROR, TIMEOUT -> ToolResultState.ERROR;
            default -> ToolResultState.SUCCESS;
        };
        return new ToolResultBlock(
                idOf(toolUse), nameOf(toolUse), List.<ContentBlock>of(TextBlock.builder().text(text).build()),
                metadata(text), state);
    }

    /** 失败块：id / 名字要照原样带回去，否则平台上找不到这是哪一次调用。 */
    private static ToolResultBlock errorBlock(ToolUseBlock toolUse, String message) {
        return new ToolResultBlock(
                idOf(toolUse),
                nameOf(toolUse),
                List.<ContentBlock>of(TextBlock.builder().text(message).build()),
                Map.of(),
                ToolResultState.ERROR);
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

    private static String idOf(ToolUseBlock toolUse) {
        return toolUse == null ? null : toolUse.getId();
    }

    private static String nameOf(ToolUseBlock toolUse) {
        return toolUse == null ? null : toolUse.getName();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 截止时间是 long：extra 里放的是 Long，缺省给 0（平台侧按「没有截止时间」处理）。 */
    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
