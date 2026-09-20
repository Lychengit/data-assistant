package com.djzy.assistant.common.permission;

import com.djzy.assistant.common.api.ApiRoute;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 能力清单里的单条接口契约（§4.8 节点 A / §19.8）。
 *
 * <p>为什么带上路由三件套：agent-service 拿到工具名后，调用时要把「服务 + 方法 + 路径」发给网关
 * （网关按三元组查注册行）。只给工具名的话，agent 就得自己维护"名字 → 路由"的映射，
 * 而那正是这个版本要消灭的东西。
 *
 * <p>为什么必须带上 {@code paramSchema}：模型侧的工具 schema 就是它（§4.8）。少了它，模型只能猜参数名，
 * 每次调用都会被接口服务 I3 以「不支持的参数」拒掉——能力清单看着对、工具却一个都用不了。
 *
 * <p>为什么还要带上 {@code scenario} 与 {@code resultSchema}：{@code paramSchema} 只回答「怎么填」。
 * 接口一多，模型还需要「什么时候该用这个接口」（否则在几个相似的接口之间乱选）和
 * 「能拿到哪些字段」（否则只能等结果回来才知道，容易把字段名编错）。这两项进工具描述，不进 schema。
 *
 * @param service 服务名
 * @param httpMethod HTTP 方法
 * @param httpPath 接口路径
 * @param name 展示名（让模型知道这个接口是干什么的）
 * @param kind 副作用等级（read / write；write 走确认流程，§19.9）
 * @param resource 业务对象
 * @param paramSchema 入参契约（{@code sys_api.param_schema} 原样带出）
 * @param scenario 适用场景与不适用场景（{@code sys_api.scenario}，可为空）
 * @param resultSchema 返回字段契约（{@code sys_api.result_schema}，可为空）
 */
public record ApiCapability(
        String service,
        String httpMethod,
        String httpPath,
        String name,
        String kind,
        String resource,
        Map<String, Object> paramSchema,
        String scenario,
        Map<String, Object> resultSchema) {

    public ApiCapability {
        // 注意：record 的字段在紧凑构造器**末尾**才赋值，所以这里绝不能用 route() 访问器——
        // 那读到的是一堆未初始化的 null（本项目实测踩过：整个 capabilities 查询 500）。
        // 先构造局部变量校验并把三元组归一，再赋值回字段。
        ApiRoute route = new ApiRoute(service, httpMethod, httpPath);
        service = route.service();
        httpMethod = route.httpMethod();
        httpPath = route.httpPath();
        name = name == null || name.isBlank() ? route.toolName() : name;
        kind = kind == null || kind.isBlank() ? "read" : kind;
        scenario = scenario == null || scenario.isBlank() ? null : scenario.trim();
        // 刻意不用 Map.copyOf：两个 schema 都是从 PG 的 JSONB 读出来的，可能带 null 值，
        // 而 copyOf 遇 null 直接 NPE——那会把整条能力清单打没（清单没了 = 模型一个工具都没有）。
        paramSchema = immutable(paramSchema);
        resultSchema = immutable(resultSchema);
    }

    /** 身份三元组。 */
    public ApiRoute route() {
        return new ApiRoute(service, httpMethod, httpPath);
    }

    /** 模型侧工具名（由路由派生）。 */
    public String toolName() {
        return route().toolName();
    }

    public boolean isWrite() {
        return "write".equalsIgnoreCase(kind);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}