package com.djzy.assistant.common.api;

import java.util.Map;
import java.util.Objects;

/**
 * 网关数据调用入口的请求体：调用方**只传身份三元组**，不传任何身份或范围（§20.1.6-5）。
 *
 * <p>为什么 service / httpMethod / httpPath 三个都要传，而不是只传路径让网关反查：网关拿它们
 * **查注册行**，查不到即拒；查到了也**只用注册行里的值**拼下游地址，绝不回落到请求体里的字符串。
 * 两者一致时等价，差别在于后者在"以后有人放宽成前缀匹配"时会把请求体变成 SSRF 跳板。
 *
 * @param service 目标服务名
 * @param httpMethod HTTP 方法
 * @param httpPath 目标接口路径
 * @param skillCode 技能发起时携带（技能白名单校验用），直连调用为 {@code null}
 * @param confirmId 写操作的用户确认凭据（§19.9 / W1），只读为空
 * @param requestId 请求编号（全链路审计串联）
 * @param args 结构化入参
 */
public record ApiCallRequest(
        String service,
        String httpMethod,
        String httpPath,
        String skillCode,
        String confirmId,
        String requestId,
        Map<String, Object> args) {

    public ApiCallRequest {
        Objects.requireNonNull(requestId, "requestId");
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /** 身份三元组是否齐全（缺任何一个都进不了解析，直接 400）。 */
    public boolean hasRoute() {
        return !isBlank(service) && !isBlank(httpMethod) && !isBlank(httpPath);
    }

    public ApiRoute route() {
        return new ApiRoute(service, httpMethod, httpPath);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}