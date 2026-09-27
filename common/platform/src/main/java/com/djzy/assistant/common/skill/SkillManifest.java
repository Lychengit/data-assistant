package com.djzy.assistant.common.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能包 manifest（§18.5.2）：必填项只有 {@code id / name / description}（脚本类再加 {@code script}）。
 *
 * <p>为什么只留这三项：写 manifest 的人常常不是写代码的人，「少写一个字段就传不上去」把上传变成了一场
 * 对着报错改 JSON 的猜谜。其余字段要么能推出来（{@code kind} 没写就看包内有没有 {@code script} 块），
 * 要么另有入口（{@code boundRoutes} 上传后在技能包页「绑定接口」里配），要么只影响展示
 * （{@code params / resources / status}）——都不该拦住上传。
 *
 * <p>两条硬规则写进结构：① 绑定接口必须都已审核通过（发布时由 {@code skill_api.approved} 落库）；
 * ② 脚本必须声明取数策略（{@code none} / {@code via_host}）与超时。
 *
 * <p>{@code boundRoutes} 写与不写是两种意思：写了就是「包自带绑定」（替换库里的），**不写是「别动库里那份」**
 * （页面「绑定接口」配的绑定不会被一次重新发布抹掉），显式写 {@code []} 才是清空。每一项是**接口身份三元组的规范文本**：{@code 服务名 方法 路径}，
 * 例如 {@code interface-doctor POST /doctor/performance}。为什么不是接口编码：编码是"第二份清单"，
 * 代码里读不出来，改个名字就可能静默绑错接口；为什么服务名也要写：两个服务可能有同名路径，
 * 只写路径等于让技能绑到"碰巧同名"的另一个接口上。
 *
 * @param script 脚本声明；{@code kind=agentic} 时可为空
 * @param version 版本号；空则发布时按内容自动生成
 * @param boundRoutes 包自带的绑定接口；空列表表示「包没声明」，重新发布时保留库中已有绑定

 * <p><b>为什么在公共模块</b>：管理端上传时要用它做校验（§18.5.2），agent 侧下发技能到工作区时也要用它解包
 * （H-06a）。同一份解包逻辑放两处迟早会走样——尤其是这里的路径净化（zip slip）与体积上限，
 * 那是安全边界，不能有第二份实现。
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

    /**
     * 包内是否**声明过** {@code boundRoutes}（显式 {@code []} 与完全不写是两回事：前者是「清空绑定」，
     * 后者是「绑定不归这个包管」——重新发布时要保留页面配的那份，见 {@code SkillPackageService.review}）。
     */
    public boolean declaresBoundRoutes() {
        return raw.containsKey("boundRoutes");
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
                kindOf(raw, script),
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

    /**
     * 类型：写了就以写的为准（写错由自动检查拦），没写就**推**——包里有 {@code script} 块就是脚本技能，
     * 否则是 agentic。「类型」这个词对上传的人没什么信息量，不该逼着每个人先学它。
     */
    private static String kindOf(Map<String, Object> raw, Map<String, Object> script) {
        String declared = text(raw.get("kind"));
        return declared != null ? declared : (script.isEmpty() ? "agentic" : "script");
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