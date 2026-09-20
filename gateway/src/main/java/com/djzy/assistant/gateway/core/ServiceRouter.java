package com.djzy.assistant.gateway.core;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.gateway.config.GatewayProperties;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * G2 服务路由（§20.3）：按 {@code sys_api.service} 走内网 DNS / docker-compose 服务名，
 * 不引入注册中心。
 *
 * <p>地址由**注册行**拼出：服务名来自 {@code sys_api.service}，路径来自 {@code sys_api.http_path}。
 * 请求体里也带有同样的两个字符串（网关要用它们查注册行），但那两个**只用于查表**，绝不参与拼 URL——
 * 区别在当前实现下看不出来（查表是精确匹配，两者必然相等），但一旦以后有人把匹配放宽成
 * 「前缀匹配」或「忽略大小写」，用请求体拼 URL 就等于把 SSRF 的口子交给调用方。
 *
 * <p>路径形态由 {@link ApiRoute} 在构造时校验（{@code ..}、{@code //}、{@code ?} 一律拒绝），
 * 服务名仍在这里做白名单校验——它会被填进主机名，比路径更危险。
 */
public final class ServiceRouter {

    /** 服务名白名单形态：杜绝用服务名拼出别的主机或路径（防注入）。 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private final String baseUrlTemplate;

    public ServiceRouter(GatewayProperties properties) {
        this(properties.getServiceBaseUrlTemplate());
    }

    public ServiceRouter(String baseUrlTemplate) {
        this.baseUrlTemplate = baseUrlTemplate;
    }

    public URI uriFor(ApiDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("缺少接口元数据");
        }
        ApiRoute route = descriptor.route();
        String base = baseUrlTemplate.replace("{service}", requireSafe(route.service(), "服务名"));
        return URI.create(base + route.httpPath());
    }

    private static String requireSafe(String value, String what) {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(what + "非法：" + value);
        }
        return value;
    }
}