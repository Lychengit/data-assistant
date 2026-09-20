package com.djzy.assistant.agentweb.gateway;

import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.agentweb.web.ApiException;
import com.djzy.assistant.common.api.ApiCallRequest;
import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.permission.ApiCapability;
import com.djzy.assistant.common.permission.Capabilities;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolInvocationRequest;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolInvoker;
import com.djzy.assistant.spi.tool.ToolSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 经网关的工具面（§4.8 节点 A / §19.7 / §19.8）。
 *
 * <p>它把能力清单里的接口翻译成模型看得见的 {@code iface_<路径派生名>} 工具，
 * 把工具调用翻译成 {@code POST /v1/gateway/api-call}。**它不做任何权限判断**——
 * 判定在网关（G3），这里只负责带凭证去问。
 *
 * <p>工具 schema 直接取自能力清单里的 {@code paramSchema}（即 {@code sys_api.param_schema}，§4.8）：
 * 模型拿到真实参数名才可能一次调通。真正的参数校验仍由接口服务 I3 强制，这里下发的 schema
 * 只承担「让模型少猜」的体验层职责。
 *
 * <p>为什么这里要维护「工具名 → 路由」：网关按「服务 + 方法 + 路径」三元组判定，而模型只回传一个
 * 工具名。这份映射由能力清单当场建立，键是派生的工具名、值是权威的三元组。
 *
 * <p>它会不会变成"缓存权限"？不会。缓存的只是"这个名字对应哪个接口"，**授权判定每次都在网关实时做**
 * （§0.3-4 零缓存不变量不受影响）：权限刚被撤销时，模型仍可能凭旧名字发起调用，网关照样 403。
 * 映射缺失时也不猜——实时回查一次能力清单，查不到就明确拒绝。
 */
public final class GatewayToolPlane implements ToolPlane {

    public static final String CAPABILITIES_PATH = "/v1/permission/capabilities";
    public static final String API_CALL_PATH = "/v1/gateway/api-call";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final GatewayClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 工具名 → 接口契约（含身份三元组）。
     *
     * <p>工具名由路径派生，所以同一个名字在任何用户眼里都对应同一个接口——这份映射是全局的，
     * 不按用户区分（用户之间不同的是"能不能调"，那是网关的事）。
     */
    private final Map<String, ApiCapability> routesByTool = new ConcurrentHashMap<>();

    public GatewayToolPlane(GatewayClient client) {
        this.client = client;
    }

    @Override
    public ToolCatalog catalogFor(String bearerToken) {
        Capabilities capabilities = fetchCapabilities(bearerToken);
        List<ToolSpec> tools = new ArrayList<>();
        for (ApiCapability api : capabilities.apis()) {
            remember(api);
            tools.add(new ToolSpec(
                    api.toolName(),
                    describe(api),
                    toolSchema(api.paramSchema()),
                    api.isWrite() ? SideEffect.WRITE : SideEffect.READ,
                    ToolCategory.IFACE,
                    Set.of("api", api.toolName())));
        }
        return ToolCatalog.of(tools);
    }

    @Override
    public ToolInvoker invokerFor(String bearerToken) {
        return request -> Mono.fromCallable(() -> invoke(bearerToken, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Capabilities fetchCapabilities(String bearerToken) {
        GatewayClient.Response response = client.get(CAPABILITIES_PATH, bearerToken);
        if (response.status() == 401) {
            throw ApiException.unauthorized();
        }
        if (!response.ok()) {
            throw ApiException.unavailable();
        }
        return parseCapabilities(response.body());
    }

    private void remember(ApiCapability api) {
        ApiCapability previous = routesByTool.put(api.toolName(), api);
        if (previous != null && !previous.route().equals(api.route())) {
            // 两行注册记录的路径派生出了同一个工具名：模型只会看到其中之一，另一个永远调不到。
            // 管理端注册时会拦住这种重名，这里是最后一道提示。
            throw new IllegalStateException("工具名冲突：{} 同时对应 " + previous.route() + " 与 " + api.route());
        }
    }

    private ToolInvocationResult invoke(String bearerToken, ToolInvocationRequest request) {
        String toolName = request.toolName();
        if (toolName == null || !toolName.startsWith(ApiRoute.TOOL_PREFIX)) {
            return pendingExtension(toolName);
        }
        ApiCapability api = routesByTool.get(toolName);
        if (api == null) {
            // 进程刚起、或这个用户本轮还没拉过能力清单：实时回查一次，绝不按名字猜一个接口去调。
            api = fetchCapabilities(bearerToken).apis().stream()
                    .filter(candidate -> candidate.toolName().equals(toolName))
                    .findFirst()
                    .orElse(null);
        }
        if (api == null) {
            return ToolInvocationResult.denied(UnifiedErrors.NOT_FOUND_OR_FORBIDDEN, "API_ROUTE_UNKNOWN");
        }
        ApiCallRequest payload = new ApiCallRequest(
                api.service(),
                api.httpMethod(),
                api.httpPath(),
                null,
                request.confirmId(),
                request.requestId(),
                request.arguments());
        GatewayClient.Response response;
        try {
            response = client.postJson(API_CALL_PATH, writeJson(apiCallBody(payload)), bearerToken);
        } catch (GatewayClient.GatewayUnavailableException e) {
            return e.isTimeout()
                    ? ToolInvocationResult.timeout(UnifiedErrors.TIMEOUT)
                    : ToolInvocationResult.error(AgentErrorCode.RUNTIME_UNAVAILABLE, "网关不可用");
        }
        if (!response.ok()) {
            return mapFailure(response);
        }
        return mapSuccess(response);
    }

    /** 技能 / 脚本工具的执行面尚未落地（M3 里程碑），先明确拒绝而不是假装能跑。 */
    private static ToolInvocationResult pendingExtension(String toolName) {
        return ToolInvocationResult.denied(UnifiedErrors.FORBIDDEN, "TOOL_KIND_NOT_IMPLEMENTED:" + toolName);
    }

    private static ToolInvocationResult mapFailure(GatewayClient.Response response) {
        return switch (response.status()) {
            case 401, 403 -> ToolInvocationResult.denied(UnifiedErrors.FORBIDDEN, "GATEWAY_DENIED");
            case 404 -> ToolInvocationResult.denied(UnifiedErrors.NOT_FOUND_OR_FORBIDDEN, "API_NOT_REGISTERED");
            case 429 -> ToolInvocationResult.error(AgentErrorCode.TOOL_DENIED, UnifiedErrors.RATE_LIMITED);
            case 408, 504 -> ToolInvocationResult.timeout(UnifiedErrors.TIMEOUT);
            default -> ToolInvocationResult.error(AgentErrorCode.TOOL_FAILED, "GATEWAY_" + response.status());
        };
    }

    private ToolInvocationResult mapSuccess(GatewayClient.Response response) {
        Map<String, Object> body = readJson(response.body());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", body.getOrDefault("rows", List.of()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("httpPath", body.get("httpPath"));
        meta.put("rowCount", body.getOrDefault("rows", List.of()) instanceof List<?> rows ? rows.size() : 0);
        meta.put("truncated", Boolean.TRUE.equals(body.get("truncated")));
        if (body.get("provenance") != null) {
            meta.put("provenance", body.get("provenance"));
        }
        return ToolInvocationResult.ok(response.body(), Map.copyOf(data), Map.copyOf(meta));
    }

    private Capabilities parseCapabilities(String body) {
        Map<String, Object> raw = readJson(body);
        return new Capabilities(
                String.valueOf(raw.get("userId")),
                stringList(raw.get("viewableSkills")),
                apiContracts(raw.get("apis")),
                java.time.Instant.now());
    }

    /** {@code apis} 是能力清单的契约明细，路由与参数契约都在里面。 */
    private static List<ApiCapability> apiContracts(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<ApiCapability> contracts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String service = stringOf(raw.get("service"));
            String httpMethod = stringOf(raw.get("httpMethod"));
            String httpPath = stringOf(raw.get("httpPath"));
            if (service == null || httpMethod == null || httpPath == null) {
                // 缺三元组的条目进不了工具面：模型拿到一个没有目标地址的工具，只会得到一个必然失败的调用。
                continue;
            }
            // 场景与返回字段契约一起解出来：它们是工具描述的两段正文（什么时候用 / 能拿到什么字段）。
            // 解析时漏掉一项，网关那边补得再全也到不了模型眼前——表现是模型仍会选错接口、编字段名。
            contracts.add(new ApiCapability(
                    service,
                    httpMethod,
                    httpPath,
                    stringOf(raw.get("name")),
                    stringOf(raw.get("kind")),
                    stringOf(raw.get("resource")),
                    asMap(raw.get("paramSchema")),
                    stringOf(raw.get("scenario")),
                    asMap(raw.get("resultSchema"))));
        }
        return contracts;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 工具描述：把「什么时候用 + 怎么填 + 能拿到什么」写进一段话。
     *
     * <p>对应 MCP 工具定义的 {@code description + inputSchema + outputSchema} 三件套。为什么要挤进一段文字：
     * OpenAI 兼容的 function calling 只把 {@code name / description / parameters} 送到模型眼前，
     * {@code outputSchema} 没有位置——所以「能拿到哪些字段」只能写在这里（§4.8 节点 A / §19.8）。
     *
     * <p>为什么不嫌长：接口少的时候模型靠猜也能调通，接口一多就会选错接口、编错字段名，
     * 代价是同一轮里连错两三次（每次都要等一个往返）。描述多几百字 = token 便宜，往返贵。
     */
    static String describe(ApiCapability api) {
        StringBuilder text = new StringBuilder("调用医生数据接口 ").append(api.toolName());
        if (api.name() != null && !api.name().isBlank() && !api.name().equals(api.toolName())) {
            text.append("（").append(api.name()).append("）");
        }
        text.append("。\n");
        if (api.scenario() != null) {
            text.append("适用场景：").append(api.scenario()).append('\n');
        }
        appendSection(text, "入参", paramLines(api.paramSchema()));
        appendSection(text, "返回字段（结果 rows 里每一行的字段）", resultLines(api.resultSchema()));
        return text.append("数据可见范围由服务端按登录人决定，调用方不需要也不能指定。").toString();
    }

    private static void appendSection(StringBuilder text, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        text.append(title).append("：\n");
        lines.forEach(line -> text.append("  - ").append(line).append('\n'));
    }

    /**
     * 入参逐条展开成一行的读法：{@code 名字（必填/可选，类型，例 xxx）：说明}。
     *
     * <p>为什么要把 {@code example} 也读出来：模型最容易错的是**取值口径**——把「心内科」翻成
     * {@code cardiology}、把「上个月」写成 {@code 2026-07}。JSON Schema 里已经有 {@code example}，
     * 但模型在长 schema 里常常只看到 {@code type}，点一句到描述里最省事。
     */
    private static List<String> paramLines(Map<String, Object> paramSchema) {
        Collection<String> required = requiredNames(paramSchema);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> entry : propertiesOf(paramSchema).entrySet()) {
            Map<String, Object> spec = specOf(entry.getValue());
            StringBuilder line = new StringBuilder(entry.getKey())
                    .append("（")
                    .append(required.contains(entry.getKey()) ? "必填" : "可选");
            Object type = spec.get("type");
            if (type != null) {
                line.append("，").append(type);
            }
            Object example = spec.get("example");
            if (example != null) {
                line.append("，例 ").append(example);
            }
            line.append("）");
            Object description = spec.get("description");
            if (description != null && !String.valueOf(description).isBlank()) {
                line.append("：").append(description);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** 返回字段逐条展开：{@code 字段名（类型）：说明}。它回答「结果里有什么、字段名怎么写」。 */
    private static List<String> resultLines(Map<String, Object> resultSchema) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Object> entry : propertiesOf(resultSchema).entrySet()) {
            Map<String, Object> spec = specOf(entry.getValue());
            String type = String.valueOf(spec.getOrDefault("type", ""));
            String description = String.valueOf(spec.getOrDefault("description", ""));
            StringBuilder line = new StringBuilder(entry.getKey());
            if (!type.isBlank()) {
                line.append("（").append(type).append("）");
            }
            if (!description.isBlank()) {
                line.append("：").append(description);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * 取出「字段名 → 字段说明」。
     *
     * <p>三种录法都要认（管理端是人录的，口径必然不止一种）：完整 JSON Schema 看 {@code properties}；
     * 扁平表（参数名 → 类型 / 字段名 → 说明）本身就是这张表；纯 {@code {"type":"object"}} 则没有字段。
     */
    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of();
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            return stringKeys(properties);
        }
        if ("object".equals(schema.get("type"))) {
            return Map.of();
        }
        return stringKeys(schema);
    }

    /** 单条字段说明：可能是对象（{@code {type, description, example}}），也可能只写了类型字符串。 */
    private static Map<String, Object> specOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stringKeys(map);
        }
        return value == null ? Map.of() : Map.of("type", String.valueOf(value));
    }

    private static Map<String, Object> stringKeys(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * {@code sys_api.param_schema} 有两种历史口径：管理端既可能录完整 JSON Schema，
     * 也可能只录「参数名 → 类型声明」的扁平表。两种都要认，否则一种录法就让工具面失效。
     */
    private static Map<String, Object> toolSchema(Map<String, Object> paramSchema) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (paramSchema == null || paramSchema.isEmpty()) {
            schema.put("additionalProperties", true);
            schema.put("description", "接口入参；字段白名单由接口服务校验，传未注册字段会被拒绝");
            return schema;
        }
        if (isFullSchema(paramSchema)) {
            // 管理端已按 JSON Schema 录入：原样透传，免得二次加工把它的约束改错
            schema.putAll(paramSchema);
            schema.put("type", "object");
            return schema;
        }
        // 扁平口径补成 object schema。additionalProperties=false 是给模型的「别造字段」提示，
        // 省掉一次「参数被拒 → 重试」的往返；真正的白名单仍在接口服务 I3，不靠这里兜底。
        schema.put("properties", paramSchema);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static boolean isFullSchema(Map<String, Object> paramSchema) {
        return paramSchema != null
                && "object".equals(paramSchema.get("type"))
                && paramSchema.get("properties") instanceof Map<?, ?>;
    }

    private static Collection<String> requiredNames(Map<String, Object> paramSchema) {
        if (paramSchema != null && paramSchema.get("required") instanceof List<?> required) {
            return required.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Map<String, Object> readJson(String body) {
        try {
            return mapper.readValue(body == null ? "{}" : body, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("工具入参序列化失败", e);
        }
    }

    /** 线格式与网关 {@code ApiCallRequest} 一致；**不含 userId / 角色**（§20.1.6-5）。 */
    private static Map<String, Object> apiCallBody(ApiCallRequest payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", payload.service());
        body.put("httpMethod", payload.httpMethod());
        body.put("httpPath", payload.httpPath());
        body.put("skillCode", payload.skillCode());
        body.put("confirmId", payload.confirmId());
        body.put("requestId", payload.requestId());
        body.put("args", payload.args());
        return body;
    }
}