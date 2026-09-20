package com.djzy.assistant.management.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能包 manifest（§18.5.2）：必填项 {@code id / 名称 / 版本 / 类型 / 描述 / 参数定义 / 绑定接口 / 脚本 / 资源 / 状态}。
 *
 * <p>两条硬规则写进结构：① 绑定接口必须都已审核通过（发布时由 {@code skill_api.approved} 落库）；
 * ② 脚本必须声明取数策略（{@code none} / {@code via_host}）与超时。
 *
 * <p>{@code boundRoutes} 里每一项是**接口身份三元组的规范文本**：{@code 服务名 方法 路径}，
 * 例如 {@code interface-doctor POST /doctor/performance}。为什么不是接口编码：编码是"第二份清单"，
 * 代码里读不出来，改个名字就可能静默绑错接口；为什么服务名也要写：两个服务可能有同名路径，
 * 只写路径等于让技能绑到"碰巧同名"的另一个接口上。
 *
 * @param script 脚本声明；{@code kind=agentic} 时可为空
 */
public record SkillManifest(
        String skillCode,
        String name,
        String version,
        String kind,
        String description,
        Map<String, Object> params,
        List<String> boundRoutes,
        ScriptSpec script,
        List<String> resources,
        List<String> exports,
        String status,
        Map<String, Object> raw) {

    /** 脚本声明（§18.5.2 硬规则② / §18.4.4 取数策略）。 */
    public record ScriptSpec(String path, String dataPolicy, int timeoutSeconds) {

        public static final String DATA_NONE = "none";
        public static final String DATA_VIA_HOST = "via_host";
    }

    public boolean isScript() {
        return "script".equalsIgnoreCase(kind);
    }

    /** 回写 config_audit / 库里的 manifest 文本（原样保存，不加工）。 */
    public Map<String, Object> toMap() {
        return raw;
    }

    /** 发布时写进 {@code sys_skill.param_schema} 的部分。 */
    public Map<String, Object> paramSchema() {
        return params == null ? Map.of() : params;
    }

    @SuppressWarnings("unchecked")
    public static SkillManifest from(Map<String, Object> raw) {
        Map<String, Object> script = raw.get("script") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
        return new SkillManifest(
                text(raw.get("id")),
                text(raw.get("name")),
                text(raw.get("version")),
                text(raw.get("kind")),
                text(raw.get("description")),
                raw.get("params") instanceof Map<?, ?> params ? new LinkedHashMap<>((Map<String, Object>) params) : Map.of(),
                strings(raw.get("boundRoutes")),
                script.isEmpty()
                        ? null
                        : new ScriptSpec(
                                text(script.get("path")),
                                text(script.get("data")),
                                number(script.get("timeoutSeconds"))),
                strings(raw.get("resources")),
                strings(raw.get("exports")),
                text(raw.get("status")),
                raw);
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}