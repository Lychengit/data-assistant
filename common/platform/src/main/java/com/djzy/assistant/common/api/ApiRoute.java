package com.djzy.assistant.common.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 接口身份三元组（§4.7）：{@code (service, http_method, http_path)}。
 *
 * <p>为什么身份是三元组而不是一个接口编码：编码是**第二份清单**——它既不在代码里有约束，
 * 也不参与路由，只能靠人记住"注册表里写的"和"代码里实现的"保持一致。三元组里每一项都能
 * 从代码本身读出来（{@code spring.application.name} 与 {@code @PostMapping} 的路径），
 * 所以它不存在"对不上"的可能：对不上就是启动失败（接口服务启动自检）。
 *
 * <p>{@code toolName()} 是**派生的**，不是独立配置项：{@code /doctor/performance} 派生
 * {@code iface_doctor_performance}。模型需要一个稳定、可读的工具名，但那不意味着要再维护一个名字。
 *
 * @param service 服务名（与 {@code spring.application.name} 一致，网关按它走固定配置发现，§20.3）
 * @param httpMethod HTTP 方法（大写归一，如 {@code POST}）
 * @param httpPath 接口服务上真实的接收路径（如 {@code /doctor/performance}）
 */
public record ApiRoute(String service, String httpMethod, String httpPath) {

    /** 接口类工具名前缀（**唯一来源**）：工具名 = 前缀 + 路径派生串。 */
    public static final String TOOL_PREFIX = "iface_";

    /**
     * 路径白名单形态：只允许段分隔符与常见标识符字符。
     *
     * <p>这条正则是**安全边界**而不是格式美观：{@code http_path} 会由网关拼进下游 URL，
     * 放任 {@code ..}、{@code //}、{@code ?}、{@code #} 这类字符等于把路径穿越/SSRF 的口子
     * 留给"能改注册表的人"。数据库层还有一条同名 CHECK 兜底（V13 迁移）。
     */
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9/_-]*$");

    public ApiRoute {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(httpPath, "httpPath");
        service = service.trim();
        httpMethod = httpMethod.trim().toUpperCase(java.util.Locale.ROOT);
        httpPath = httpPath.trim();
        if (!SAFE_PATH.matcher(httpPath).matches() || httpPath.contains("//") || httpPath.contains("..")) {
            throw new IllegalArgumentException("接口路径非法：" + httpPath);
        }
    }

    /** 模型侧工具名：{@code iface_} + 路径派生串（{@code /doctor/performance} → {@code iface_doctor_performance}）。 */
    public String toolName() {
        return TOOL_PREFIX + slug();
    }

    /** 路径派生串：去掉前导斜杠、把段分隔符换成下划线。 */
    public String slug() {
        return httpPath.substring(1).replace('/', '_');
    }

    /** 审计与日志里的一行式标识。 */
    public String display() {
        return httpMethod + " " + httpPath;
    }

    /**
     * 可解析的规范文本形式：{@code service METHOD /path}（三段以空白分隔）。
     *
     * <p>存在的理由：技能包 manifest 是**人写的文本**，绑定接口时不能只写路径——两个服务可能有同名路径，
     * 只写路径等于让技能绑到"碰巧同名"的另一个接口上。三段全写死，绑错就是启动/上传时报错，
     * 而不是运行期静默调错服务。
     */
    public String format() {
        return service + " " + httpMethod + " " + httpPath;
    }

    /** {@link #format()} 的逆运算；形状不对直接抛错（fail-closed，不猜）。 */
    public static ApiRoute parse(String text) {
        String[] parts = text == null ? new String[0] : text.trim().split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "接口标识必须写成「服务名 方法 路径」三段（例：interface-doctor POST /doctor/performance）：" + text);
        }
        return new ApiRoute(parts[0], parts[1], parts[2]);
    }

    @Override
    public String toString() {
        return service + " " + httpMethod + " " + httpPath;
    }
}