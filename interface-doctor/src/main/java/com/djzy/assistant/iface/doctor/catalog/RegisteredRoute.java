package com.djzy.assistant.iface.doctor.catalog;

import com.djzy.assistant.common.api.ApiKind;
import java.util.Objects;

/**
 * 启动自检后留在内存里的注册快照（{@code sys_api} 里属于本服务的一行）。
 *
 * <p>为什么要把注册表读进来而不是每次请求查库：请求路径上不需要它（路由由 Spring 决定），
 * 但**写操作确认门**要判 {@code kind}、"审计记哪个端点"要判路径——这些是每次请求都用、却从不变化的东西。
 * 读一次存下来，接口代码里就不必再写第二份 {@code kind} 声明（那正是 {@code @RegisteredApi} 想干的事，
 * 而它是要人工同步的第二份清单）。
 *
 * @param httpMethod HTTP 方法（大写）
 * @param httpPath 接口路径
 * @param kind 副作用等级（§4.6）：只决定确认 / 超时 / 重试，**不是权限**
 */
public record RegisteredRoute(String httpMethod, String httpPath, ApiKind kind) {

    public RegisteredRoute {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(httpPath, "httpPath");
        kind = kind == null ? ApiKind.READ : kind;
    }

    /** 集合比对用的键：{@code POST /doctor/performance}。 */
    public String key() {
        return httpMethod + " " + httpPath;
    }

    public static String keyOf(String httpMethod, String httpPath) {
        return httpMethod + " " + httpPath;
    }
}