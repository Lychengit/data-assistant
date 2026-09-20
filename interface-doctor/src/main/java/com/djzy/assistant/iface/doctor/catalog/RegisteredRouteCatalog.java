package com.djzy.assistant.iface.doctor.catalog;

import com.djzy.assistant.common.api.ApiKind;
import com.djzy.assistant.iface.doctor.config.DoctorInterfaceProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 本服务已注册接口的快照 + **启动自检**（§18.4.5 I0）。
 *
 * <p>它解决的是"两份清单静默漂移"：以前「{@code sys_api} 里登记了哪些接口」和「代码里实现了哪些接口」
 * 之间没有任何东西校验，只会在线上调用时报错。现在启动时对账，不一致就**拒绝启动**并打印差异。
 *
 * <p>对账的左边是 Spring 自己的路由表（{@code RequestMappingHandlerMapping}）——不是扫注解、
 * 不是扫包名，而是框架用来匹配请求的那份数据本身，所以代理类、条件装配、类级前缀拼接都算得进去。
 *
 * <p>顺带核对两件只有启动时才能便宜地发现的事：
 * <ol>
 *   <li>每个注册路径都落在验签过滤器的保护前缀内（否则那个端点会裸奔：网关能调、别人也能）；
 *   <li>每个接口的 DTO 字段与 {@code sys_api.param_schema} 的 {@code properties} 完全一致
 *       （schema 是喂给模型的说明书，DTO 是真正的入参边界，两边不一致＝模型按说明书传参却被拒）。
 * </ol>
 *
 * <p>快照读进来后留在内存里：写操作确认门要用 {@code kind}，审计要用路径。读一次存下来，
 * 接口代码里就不必再声明第二份 {@code kind}。
 */
@Component
public class RegisteredRouteCatalog implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RegisteredRouteCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 框架自带的端点，不属于"业务接口"，不参与对账。 */
    private static final List<String> FRAMEWORK_PREFIXES = List.of("/error", "/actuator");

    private final RequestMappingHandlerMapping handlerMapping;
    private final JdbcTemplate jdbc;
    private final DoctorInterfaceProperties properties;
    private final String serviceName;

    private volatile Map<String, RegisteredRoute> routes = Map.of();

    public RegisteredRouteCatalog(
            RequestMappingHandlerMapping handlerMapping,
            JdbcTemplate jdbc,
            DoctorInterfaceProperties properties,
            @Value("${spring.application.name}") String serviceName) {
        this.handlerMapping = handlerMapping;
        this.jdbc = jdbc;
        this.properties = properties;
        this.serviceName = serviceName;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Method> actual = scanHandlers();
        Map<String, RegisteredRoute> declared = loadDeclared();
        verifySameSet(actual.keySet(), declared.keySet());
        verifyProtected(declared.values());
        declared.forEach((key, route) -> verifyDtoMatchesSchema(key, actual.get(key), loadSchema(route.httpPath())));
        // 对账用的是「方法 + 路径」的键，而查路由时只有一个路径——所以这里必须**换一份键**：
        // 存成 key() 会让 find(path) 永远查不到（切面于是静默不记账，实测踩过）。
        Map<String, RegisteredRoute> byPath = new LinkedHashMap<>();
        declared.values().forEach(route -> byPath.put(route.httpPath(), route));
        this.routes = Map.copyOf(byPath);
        log.info("接口注册自检通过：service={} 接口数={} routes={}", serviceName, routes.size(), routes.keySet());
    }

    /** 请求路径 → 注册快照。找不到说明启动自检被绕过了，正常永远找得到。 */
    public Optional<RegisteredRoute> find(String httpPath) {
        return Optional.ofNullable(routes.get(httpPath));
    }

    public RegisteredRoute require(String httpPath) {
        RegisteredRoute route = routes.get(httpPath);
        if (route == null) {
            throw new IllegalStateException("路径未注册（启动自检应当拦住这种配置）：" + httpPath);
        }
        return route;
    }

    // ---------- 左边：代码里实际的接口 ----------

    private Map<String, Method> scanHandlers() {
        Map<String, Method> handlers = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> entry :
                handlerMapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = entry.getKey().getPathPatternsCondition() == null
                    ? Set.of()
                    : entry.getKey().getPathPatternsCondition().getPatternValues();
            // 先把框架自带的端点摘掉再判 HTTP 方法：Spring Boot 的 BasicErrorController#error 用的是裸
            // @RequestMapping（没有方法限定），先调 verbOf 会让服务在启动时直接崩——而不是"少对账一条"。
            Set<String> business = new java.util.LinkedHashSet<>(patterns);
            business.removeIf(RegisteredRouteCatalog::isFrameworkPath);
            if (business.isEmpty()) {
                continue;
            }
            String verb = verbOf(entry.getValue().getMethod());
            for (String pattern : business) {
                String key = RegisteredRoute.keyOf(verb, pattern);
                Method previous = handlers.put(key, entry.getValue().getMethod());
                if (previous != null) {
                    throw new IllegalStateException("同一路径被实现了两次：" + key);
                }
            }
        }
        return handlers;
    }

    /**
     * 接口必须显式声明 HTTP 方法（{@code @GetMapping} / {@code @PostMapping} …）。
     *
     * <p>不接受裸 {@code @RequestMapping}：三元组里的方法项是身份的一部分，留空意味着"任何方法都行"，
     * 那样一条注册记录会同时匹配 GET 与 POST，授权粒度就模糊了。
     */
    private static String verbOf(Method method) {
        for (Map.Entry<Class<? extends Annotation>, String> entry : Map.of(
                        GetMapping.class, "GET",
                        PostMapping.class, "POST",
                        PutMapping.class, "PUT",
                        DeleteMapping.class, "DELETE",
                        PatchMapping.class, "PATCH")
                .entrySet()) {
            if (AnnotatedElementUtils.hasAnnotation(method, entry.getKey())) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException(
                "接口必须显式声明 HTTP 方法（@GetMapping/@PostMapping…）：" + method.getDeclaringClass().getName()
                        + "#" + method.getName());
    }

    private static boolean isFrameworkPath(String pattern) {
        return FRAMEWORK_PREFIXES.stream().anyMatch(pattern::startsWith);
    }

    // ---------- 右边：注册表里声明的接口 ----------

    private Map<String, RegisteredRoute> loadDeclared() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT http_method, http_path, kind FROM sys_api WHERE service = ? ORDER BY http_path", serviceName);
        Map<String, RegisteredRoute> declared = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String method = String.valueOf(row.get("http_method")).toUpperCase(java.util.Locale.ROOT);
            String path = String.valueOf(row.get("http_path"));
            RegisteredRoute route = new RegisteredRoute(
                    method, path, "write".equalsIgnoreCase(String.valueOf(row.get("kind"))) ? ApiKind.WRITE : ApiKind.READ);
            declared.put(route.key(), route);
        }
        return declared;
    }

    private Map<String, Object> loadSchema(String httpPath) {
        List<String> schemas = jdbc.queryForList(
                "SELECT param_schema::text FROM sys_api WHERE service = ? AND http_path = ?",
                String.class,
                serviceName,
                httpPath);
        if (schemas.isEmpty() || schemas.get(0) == null || schemas.get(0).isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(schemas.get(0), MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("sys_api.param_schema 不是合法 JSON：" + httpPath, e);
        }
    }

    // ---------- 对账 ----------

    private static void verifySameSet(Set<String> actual, Set<String> declared) {
        Set<String> missingInRegistry = new TreeSet<>(actual);
        missingInRegistry.removeAll(declared);
        Set<String> missingInCode = new TreeSet<>(declared);
        missingInCode.removeAll(actual);
        if (missingInRegistry.isEmpty() && missingInCode.isEmpty()) {
            return;
        }
        throw new IllegalStateException("接口注册与实现不一致（启动自检，§18.4.5 I0）："
                + " 代码里实现了但 sys_api 没登记=" + missingInRegistry
                + "；sys_api 登记了但代码没实现=" + missingInCode);
    }

    private void verifyProtected(java.util.Collection<RegisteredRoute> declared) {
        String prefix = properties.getApiPrefix();
        List<String> uncovered = new ArrayList<>();
        for (RegisteredRoute route : declared) {
            if (!route.httpPath().startsWith(prefix + "/")) {
                uncovered.add(route.key());
            }
        }
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException("以下接口不在验签保护前缀 " + prefix
                    + " 内，等于把端点敞开给任何能连上本服务的人：" + uncovered);
        }
    }

    /**
     * 注册表里给模型看的 {@code param_schema}，与代码里真正的入参 DTO，必须描述同一组字段。
     *
     * <p>不校验的话，最常见的坏结果是"模型按说明书传 {@code metric_key}，代码里叫 {@code metricKey}，
     * 每次都 400"——而且改任何一边都不会有任何提示。
     */
    private void verifyDtoMatchesSchema(String key, Method handler, Map<String, Object> schema) {
        Class<?> argsType = argsTypeOf(handler);
        Set<String> dtoProps = dtoProperties(argsType);
        Set<String> schemaProps = schemaProperties(schema);
        if (schemaProps.isEmpty()) {
            log.warn("接口 {} 的 sys_api.param_schema 没有 properties，模型看不到参数说明（跳过字段对账）", key);
            return;
        }
        if (!dtoProps.equals(schemaProps)) {
            Set<String> onlyInDto = new TreeSet<>(dtoProps);
            onlyInDto.removeAll(schemaProps);
            Set<String> onlyInSchema = new TreeSet<>(schemaProps);
            onlyInSchema.removeAll(dtoProps);
            throw new IllegalStateException("接口 " + key + " 的入参 DTO 与 sys_api.param_schema 字段不一致："
                    + " 代码里有但 schema 没写=" + onlyInDto + "；schema 写了但代码没有=" + onlyInSchema);
        }
        for (String required : requiredProperties(schema)) {
            if (!hasRequiredConstraint(argsType, required)) {
                throw new IllegalStateException("接口 " + key + " 的 param_schema 把 " + required
                        + " 标为必填，但 DTO 上没有 @NotNull/@NotBlank 之类的约束");
            }
        }
    }

    private static Class<?> argsTypeOf(Method handler) {
        for (int i = 0; i < handler.getParameterCount(); i++) {
            Parameter parameter = handler.getParameters()[i];
            if (!parameter.isAnnotationPresent(RequestBody.class)) {
                continue;
            }
            ResolvableType envelope = ResolvableType.forMethodParameter(new MethodParameter(handler, i));
            Class<?> args = envelope.getGeneric(0).resolve();
            if (args == null) {
                throw new IllegalStateException("无法解析 " + handler.getName()
                        + " 的入参类型，请显式写成 ApiEnvelope<XxxArgs>（不要用裸类型）");
            }
            return args;
        }
        throw new IllegalStateException("注册接口必须用 @RequestBody ApiEnvelope<XxxArgs> 接收信封：" + handler.getName());
    }

    private static Set<String> dtoProperties(Class<?> argsType) {
        RecordComponent[] components = argsType.getRecordComponents();
        if (components == null) {
            throw new IllegalStateException("业务入参必须是 record（这样才能与 param_schema 机械对账）：" + argsType.getName());
        }
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : components) {
            names.add(jsonName(component));
        }
        return names;
    }

    /**
     * record 组件的 JSON 名。
     *
     * <p>**注解不在组件上**：Java 只会把组件上的注解传播到「字段 / 访问器 / 构造参数」这三处，
     * 而 {@code @JsonProperty} 的可标注位置正好不包含 RECORD_COMPONENT，所以
     * {@code component.getAnnotation(JsonProperty.class)} 永远是 null。只看组件就会得出
     * "代码里叫 deptCode、说明书里叫 dept_code"的假差异，把服务挡在启动之外（实测踩过）。
     */
    private static String jsonName(RecordComponent component) {
        com.fasterxml.jackson.annotation.JsonProperty json = jsonOf(component);
        return json != null && !json.value().isEmpty() ? json.value() : component.getName();
    }

    private static com.fasterxml.jackson.annotation.JsonProperty jsonOf(RecordComponent component) {
        com.fasterxml.jackson.annotation.JsonProperty onComponent =
                component.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        Method accessor = component.getAccessor();
        return onComponent != null
                ? onComponent
                : accessor == null ? null : accessor.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
    }

    private static boolean hasRequiredConstraint(Class<?> argsType, String property) {
        for (RecordComponent component : argsType.getRecordComponents()) {
            if (!property.equals(jsonName(component))) {
                continue;
            }
            if (hasRequiredAnnotation(component.getAnnotations())
                    || (component.getAccessor() != null && hasRequiredAnnotation(component.getAccessor().getAnnotations()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRequiredAnnotation(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String type = annotation.annotationType().getName();
            if (type.endsWith(".NotNull") || type.endsWith(".NotBlank") || type.endsWith(".NotEmpty")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> schemaProperties(Map<String, Object> schema) {
        Object properties = schema == null ? null : schema.get("properties");
        if (!(properties instanceof Map<?, ?> map)) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        map.keySet().forEach(key -> names.add(String.valueOf(key)));
        return names;
    }

    private static List<String> requiredProperties(Map<String, Object> schema) {
        Object required = schema == null ? null : schema.get("required");
        if (!(required instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        list.forEach(value -> names.add(String.valueOf(value)));
        return names;
    }
}