package com.djzy.assistant.common.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 接口注册元数据（{@code sys_api}，§4.7 / §18.4.6 M4）。
 *
 * <p>身份 = {@link #route()}（服务 + 方法 + 路径），不是某个编码；{@code id} 只用于授权比对
 * （{@code role_api.api_id}），调用方永远只传三元组。
 *
 * <p>这份元数据有**两个读者**，字段因此分成两组：
 * <ul>
 *   <li>平台自己用（不喂给模型）：{@code service} / {@code httpMethod} / {@code httpPath}（网关按它转发，§20.3）、
 *       {@code kind}（确认 / 超时 / 重试，§4.6）。
 *   <li>模型要用（经 §19.8 capabilities 进工具描述与入参 schema）：{@code name}、{@code scenario}、
 *       {@code paramSchema}、{@code resultSchema}。
 * </ul>
 *
 * <p>为什么必须有 {@code scenario} 与 {@code resultSchema}：接口少的时候模型靠猜也能调通，接口一多就会
 * 选错接口、用错字段。{@code paramSchema} 只回答「怎么填」，回答不了「什么时候用」（选择）与
 * 「能拿到什么」（结果解读）——后两点以前只存在于人的脑子里，模型只能靠试。
 *
 * <p>为什么没有「列白名单」与「数据范围」：返回哪些列由接口自己的 SQL 与返回类型写死（比运行时白名单更硬，
 * 多返回一列在编译期就不可能）；数据范围在接口服务内基于登录人推导，不在这里配置。
 *
 * @param id 注册行主键（授权比对的键）
 * @param name 展示名
 * @param service 服务名：网关按服务名走内网 DNS / docker-compose 服务名转发（§20.3）
 * @param httpMethod HTTP 方法（大写归一）
 * @param httpPath 接口服务上真实的接收路径
 * @param kind 副作用等级（read / write）
 * @param resource 业务对象（医生 / 科室 …）
 * @param paramSchema 入参 schema（接口服务 I3 校验用；同时是模型侧工具 schema）
 * @param enabled 是否启用
 * @param scenario 适用场景：什么时候该用、什么时候不该用（进模型工具描述）
 * @param resultSchema 返回字段契约：字段名 → {@code {type, description}}（进模型工具描述）
 */
public record ApiDescriptor(
        long id,
        String name,
        String service,
        String httpMethod,
        String httpPath,
        ApiKind kind,
        String resource,
        Map<String, Object> paramSchema,
        boolean enabled,
        String scenario,
        Map<String, Object> resultSchema) {

    public ApiDescriptor {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(kind, "kind");
        ApiRoute route = new ApiRoute(service, httpMethod, httpPath);
        service = route.service();
        httpMethod = route.httpMethod();
        httpPath = route.httpPath();
        name = name == null || name.isBlank() ? route.toolName() : name;
        // param_schema / result_schema 都是 PG 的 jsonb，可能带 null 值；Map.copyOf 遇 null 直接 NPE，
        // 那会把整条接口元数据打没（能力清单没了 = 模型一个工具都没有）。
        paramSchema = immutable(paramSchema);
        resultSchema = immutable(resultSchema);
        scenario = scenario == null || scenario.isBlank() ? null : scenario.trim();
    }

    /** 接口身份三元组。 */
    public ApiRoute route() {
        return new ApiRoute(service, httpMethod, httpPath);
    }

    /** 该接口在 agent 侧的工具名（由路由派生，不是独立配置项）。 */
    public String toolName() {
        return route().toolName();
    }

    public ApiDescriptor withEnabled(boolean value) {
        return new ApiDescriptor(
                id, name, service, httpMethod, httpPath, kind, resource, paramSchema, value, scenario, resultSchema);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}