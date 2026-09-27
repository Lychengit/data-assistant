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
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <h2>为什么继承 {@code ToolBase}（T1-12 的结论在 2026-09-27 被推翻一次）</h2>
 *
 * <p>原来这里是 legacy {@code AgentTool} 的直实现，注释里写着「框架的权限门只认 {@code ToolBase}
 * 的子类，所以框架永远不会为平台工具发确认事件，要做工具级硬拒绝得先继承 {@code ToolBase}」。
 * 那句判断本身是对的，但结论用错了地方：**写操作的用户确认正是靠这条门**。
 * 不继承的结果是实测出来的——对话里模型调 {@code POST /doctor/export/upload}，
 * 网关 G3 直接 403（审计 reason = {@code CONFIRM_REQUIRED}），平台上连一张确认卡都发不出来。
 *
 * <p>继承之后：框架在**执行之前**逐个调 {@link #checkPermissions}，写操作得到 {@code ASK}，
 * 框架据此把这次调用标成 {@code ASKING}、发 {@code REQUIRE_USER_CONFIRM} 并挂起这一轮；
 * 用户点确认，平台登记一次性凭据（§19.9），续跑那一轮把凭据带给网关。
 *
 * <p><b>这里只回答「要不要问用户」，不回答「有没有权限」</b>。权限（谁能不能调哪个接口）永远只有
 * 网关一个判定点（§4.8 节点 E / DR-21）。把两层混在一起，就等于在运行时里又造了一个权限中心。
 */
final class PlatformToolAdapter extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(PlatformToolAdapter.class);

    /** 模型给路径、宿主代理读字节时用的入参名（见 {@link #fillContentFromSandbox}）。 */
    static final String SANDBOX_PATH_ARG = "sandbox_path";

    /** 宿主代理会替模型填好的入参名（见 {@link #modelFacingSchema}）。 */
    static final String CONTENT_BASE64_ARG = "content_base64";

    /** 模型面的 {@code sandbox_path} 说明：把「只给路径就行」写进 schema，而不是只写在技能文档里。 */
    private static final String SANDBOX_PATH_HINT =
            "沙箱内产物路径（如 /workspace/out/perf.xlsx）。**推荐只给这个**：宿主代理会按路径把文件读出来，"
                    + "不需要你自己提供 content_base64。";

    /** 模型面的 {@code content_base64} 说明：它不是模型该填的东西。 */
    private static final String CONTENT_BASE64_HINT =
            "不用填：给了 sandbox_path 时由宿主代理把文件读出来填好。只有在没有 sandbox_path 时才自己提供内容。";

    private final ToolSpec spec;

    PlatformToolAdapter(ToolSpec spec) {
        // readOnly 直接取副作用声明：这不是给模型看的提示，而是**给框架看的**只读标记。
        // 用位置构造器而不是 builder：框架 2.0.3 的 ToolBase.Builder **没有 build()**，
        // 它只负责把参数搬给这个构造器（源码里就是这么写的），照它抄最省事也最不容易抄错。
        super(
                spec.name(),
                spec.description(),
                modelFacingSchema(spec.inputSchema()),
                !spec.sideEffect().isWrite(),
                true,
                false,
                null,
                false,
                false);
        this.spec = spec;
    }

    /**
     * 模型面的入参 schema：把「宿主代理会替模型填」的字段从 {@code required} 里摘掉。
     *
     * <p>为什么非摘不可：接口的**注册契约**（{@code sys_api.param_schema}）里 {@code content_base64}
     * 是必填——对接口服务来说它确实是必填（宿主代理一定会填上）。但模型面照抄这份契约，
     * 就出现了自相矛盾：技能文档让模型「只给路径、不要抄 base64」，框架的入参校验却要求
     * {@code content_base64}。实测（2026-09-27）模型照文档只给路径，调用在**平台管线之前**
     * 就被框架校验打回（{@code 未找到所需属性"content_base64"}），写操作一次都没发出去——
     * 用户看到的是「点了确认还是失败」，还会被逼着再确认第二次（模型重试）。
     *
     * <p>为什么只摘 {@code required}、不删属性：删了属性、又带着 {@code additionalProperties:false}，
     * 模型一旦顺手填了它就会被判非法——比放宽要求更糟。同时改写两个字段的说明，让模型不必猜。
     *
     * <p>只改模型看到的这一份，不动注册契约：接口服务对 {@code content_base64} 的非空校验照旧，
     * 注册表与 DTO 的对账（启动自检）也因此不受影响。
     */
    static Map<String, Object> modelFacingSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return schema == null ? Map.of() : schema;
        }
        Object rawProperties = schema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties) || !properties.containsKey(SANDBOX_PATH_ARG)) {
            return schema;
        }
        Map<String, Object> relaxed = new LinkedHashMap<>(schema);
        Map<String, Object> modelProperties = new LinkedHashMap<>();
        properties.forEach((key, value) -> modelProperties.put(String.valueOf(key), value));
        modelProperties.computeIfPresent(SANDBOX_PATH_ARG, (key, value) -> withDescription(value, SANDBOX_PATH_HINT));
        modelProperties.computeIfPresent(CONTENT_BASE64_ARG, (key, value) -> withDescription(value, CONTENT_BASE64_HINT));
        relaxed.put("properties", Map.copyOf(modelProperties));
        Object required = schema.get("required");
        if (required instanceof List<?> list && list.contains(CONTENT_BASE64_ARG)) {
            relaxed.put(
                    "required",
                    list.stream()
                            .filter(item -> !CONTENT_BASE64_ARG.equals(item))
                            .toList());
        }
        return Map.copyOf(relaxed);
    }

    /** 换掉一个属性对象的说明文字，其余字段原样保留（属性本身可能是任意 JSON Schema 片段）。 */
    private static Object withDescription(Object property, String description) {
        if (!(property instanceof Map<?, ?> map)) {
            return property;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        copy.put("description", description);
        return Map.copyOf(copy);
    }

    /**
     * 工具自检（§19.9 / §18.4.5 W1）：写操作**执行前**必须先问用户。
     *
     * <p>为什么只对写操作 ASK：读操作问一遍等于把「查个数」也变成一次人工审批，用户会直接关掉这个功能；
     * 而写操作（对外产生副作用、留下工件）本来就该有一次明确的人工点头。
     *
     * <p>为什么读操作回 {@code passthrough} 而不是 {@code allow}：透传表示「我这一层没有意见」，
     * 判定权留给网关；回 {@code allow} 则等于替网关答应了。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (spec.sideEffect().isWrite()) {
            return Mono.just(PermissionDecision.ask(spec.name()));
        }
        return Mono.just(PermissionDecision.passthrough(spec.name()));
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
        Map<String, Object> input = param.getInput() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(param.getInput());
        Optional<String> artifactFailure = fillContentFromSandbox(input, attributes);
        if (artifactFailure.isPresent()) {
            // 读不到文件就**不要**往下走：放过去只会变成一次"上传成功、内容是空"的写操作，
            // 用户拿到一个打不开的链接，比直接失败更难查（2026-09-27 实测的坏文件就是这个味道）。
            return Mono.just(errorBlock(toolUse, artifactFailure.get()));
        }
        ToolInvocationRequest invocation = new ToolInvocationRequest(
                spec.name(),
                input,
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
     * 沙箱产物的写操作：模型只给路径，字节由**宿主代理**读出来（§18.5.3）。
     *
     * <p>约定：入参里带 {@code sandbox_path} 且没带 {@code content_base64} 时，就在沙箱里把那份文件
     * 读出来、base64 后填进 {@code content_base64}，并把 {@code sandbox_path} 摘掉——
     * 接口服务只认 {@code content_base64}，多带一个字段只会让入参对不上它的 schema。
     *
     * <p>为什么必须有这条路：让模型搬运 8.5KB 的不透明字符串，实测会翻车（它打印了截断预览，
     * 再凭碎片拼出一个 224 字节的坏 xlsx）。路径是短的、可校验的，字节是不可见的长串——只让模型碰前者。
     *
     * @return 失败原因；空 = 没有需要补的内容（不涉及沙箱产物，或已经带了 content_base64）
     */
    private static Optional<String> fillContentFromSandbox(
            Map<String, Object> input, Map<String, Object> attributes) {
        Object path = input.get(SANDBOX_PATH_ARG);
        if (path == null || String.valueOf(path).isBlank()) {
            return Optional.empty();
        }
        // 给了路径就**以路径为准**，无视模型同时填的 content_base64：它抄长串抄错过一次
        // （2026-09-27 拼出 224 字节的坏文件），路径是短的、能对得上的那一份。
        Object reader = attributes.get(CallAttributes.SANDBOX_ARTIFACT_READER);
        if (!(reader instanceof SandboxArtifactReader sandboxReader)) {
            return Optional.empty();
        }
        Optional<byte[]> content = sandboxReader.read(String.valueOf(path));
        if (content.isEmpty()) {
            return Optional.of("没有在沙箱里找到文件：" + path + "；请先确认脚本已经生成它，再重试上传");
        }
        // 摘掉 sandbox_path 再发车：它是平台与模型之间的约定，不属于接口的入参。
        input.remove(SANDBOX_PATH_ARG);
        input.put("content_base64", java.util.Base64.getEncoder().encodeToString(content.get()));
        if (input.get("file_name") == null || String.valueOf(input.get("file_name")).isBlank()) {
            String name = String.valueOf(path);
            int slash = name.lastIndexOf('/');
            input.put("file_name", slash < 0 ? name : name.substring(slash + 1));
        }
        return Optional.empty();
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