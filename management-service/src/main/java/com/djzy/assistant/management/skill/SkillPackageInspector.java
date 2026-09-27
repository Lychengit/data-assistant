package com.djzy.assistant.management.skill;

import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.common.skill.PackageContent;
import com.djzy.assistant.common.skill.SkillManifest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 自动检查（§18.4.6 M3）：**危险能力 + 额外网络调用 + 绑定接口最小性 + 导出字段合规**。
 *
 * <p>分清两类结论（这一点很重要）：
 * <ul>
 *   <li>{@code blocking} 是**结构性与最小性**约束（manifest 必填、脚本取数策略、绑定接口是注册过的只读接口、
 *       导出列在绑定接口的返回字段契约内）——这些是「说不清楚就不许上线」，必须拦；
 *   <li>{@code warning} 是**静态提示**（可疑网络调用 / 子进程 / 混淆迹象）。按 §18.12.2，
 *       静态扫描**不是安全边界**，所以它只提示评审人，不冒充拦截。
 * </ul>
 *
 * <p>绑定接口用**身份三元组的规范文本**（{@code 服务名 方法 路径}）而不是接口编码：编码是"第二份清单"，
 * 代码里读不出来、改名就可能静默绑错接口；三元组每一项都能从接口服务代码里读出来，绑错就是检查不通过。
 *
 * <p>导出列的上界来自**绑定接口的返回字段契约**（{@code sys_api.result_schema} 的字段名）而不是列白名单：
 * 返回哪些列由接口自己的 SQL 写死，白名单只会变成第三份需要手工同步的清单。
 */
public final class SkillPackageInspector {

    private static final Pattern SKILL_CODE = Pattern.compile("[a-z][a-z0-9_]{2,63}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(\\.[0-9]+){0,3}(-[a-zA-Z0-9.]+)?");
    private static final Pattern SCRIPT_EXT = Pattern.compile(".*\\.(py|sh|js|ts|jar)$");

    /** 可疑能力提示（§18.12.2 六类里的五类：外发 / 子进程 / 反向 shell / 持久化 / 混淆）。 */
    private static final Map<String, Pattern> EGRESS_HINTS = Map.of(
            "NETWORK_EGRESS", Pattern.compile("(socket\\.|urllib|requests\\.|httpx|http\\.client|curl\\s|wget\\s|nc\\s+-)", Pattern.CASE_INSENSITIVE),
            "SUBPROCESS_SHELL", Pattern.compile("(subprocess|os\\.system|os\\.popen|pty\\.spawn|exec\\()", Pattern.CASE_INSENSITIVE),
            "REVERSE_SHELL", Pattern.compile("(/dev/tcp/|bash\\s+-i|socat\\s)", Pattern.CASE_INSENSITIVE),
            "PERSISTENCE", Pattern.compile("(crontab|systemd|\\.bashrc|run\\.py\\s*&)", Pattern.CASE_INSENSITIVE),
            "OBFUSCATED", Pattern.compile("(eval\\(|exec\\(|base64\\s+-d|marshal\\.loads|\\\\x[0-9a-f]{2}\\\\x[0-9a-f]{2})", Pattern.CASE_INSENSITIVE));

    /**
     * manifest 必填项（§18.5.2）：只有 {@code id / name / description}，再加按类型适用的 {@code script}。
     *
     * <p>原来这里是十项（版本 / 类型 / 参数定义 / 绑定接口 / 资源 / 状态都必填）。2026-09-27 起只留四项：
     * 上传的人往往不是写代码的人，十项必填意味着十次「少写一个就被驳回」，而其余六项各有出路——
     * 版本可自动生成、类型可从 {@code script} 块推出来、绑定接口上传后在技能包页「绑定接口」里配、
     * 参数与资源只影响 SKILL.md 的展示、状态根本没人读。必填项多一项，就多一分「对着报错改 JSON」。
     */
    public static final List<String> REQUIRED_FIELDS = List.of("id", "name", "description", "script");

    private SkillPackageInspector() {}

    /**
     * @param apis 绑定接口在 {@code sys_api} 里的元数据（{@link ApiRoute#format() 规范三元组文本} → 只读/启用/返回字段）；
     *             缺失即未注册
     */
    public static List<SkillCheckResult> inspect(PackageContent content, Map<String, ApiMetadata> apis) {
        List<SkillCheckResult> results = new ArrayList<>();
        SkillManifest manifest = content.manifest();
        results.add(manifestCompleteness(manifest));
        results.add(skillCodeFormat(manifest));
        results.add(versionFormat(manifest));
        results.add(kindKnown(manifest));
        results.add(boundRoutesRegistered(manifest, apis));
        results.add(scriptPolicy(manifest));
        results.add(scriptPresent(content));
        results.add(exportsDeclared(manifest, apis));
        results.addAll(egressHints(content));
        return List.copyOf(results);
    }

    /**
     * 通过时只说「结论」，不通过时才说「为什么不通过」。
     *
     * <p>为什么非要这样：detail 是给评审人**逐行读**的，一行通过项还挂着「版本号格式不合法」这种
     * 失败措辞，整页读下来就像一串报错——评审人要么被误导，要么干脆不看了。
     */
    private static String detail(boolean ok, String okText, String failText) {
        return ok ? okText : failText;
    }

    private static SkillCheckResult manifestCompleteness(SkillManifest manifest) {
        List<String> missing = new ArrayList<>();
        boolean scriptKind = manifest.isScript();
        for (String field : REQUIRED_FIELDS) {
            // 「脚本」这一项按 kind 适用：agentic 技能本来就没有脚本，缺它不是缺项（§18.5.2）。
            if ("script".equals(field) && !scriptKind) {
                continue;
            }
            Object value = manifest.raw().get(field);
            boolean empty = value == null
                    || (value instanceof String s && s.isBlank())
                    || (value instanceof List<?> list && list.isEmpty())
                    || (value instanceof Map<?, ?> map && map.isEmpty());
            if (empty) {
                missing.add(field);
            }
        }
        return SkillCheckResult.blocking(
                "MANIFEST_REQUIRED_FIELDS",
                missing.isEmpty(),
                detail(missing.isEmpty(), "必填项齐全", "缺失项：" + missing));
    }

    private static SkillCheckResult skillCodeFormat(SkillManifest manifest) {
        boolean ok = manifest.skillCode() != null && SKILL_CODE.matcher(manifest.skillCode()).matches();
        return SkillCheckResult.blocking(
                "SKILL_CODE_FORMAT",
                ok,
                detail(ok, "技能 id 合法：" + manifest.skillCode(),
                        "技能 id 必须是 [a-z][a-z0-9_]{2,63}，当前：" + manifest.skillCode()));
    }

    private static SkillCheckResult versionFormat(SkillManifest manifest) {
        // 不写版本不算错：发布时按包内容哈希自动生成（内容没变就是同一个包，自动版本也不会撞）。
        if (manifest.version() == null) {
            return SkillCheckResult.blocking("VERSION_FORMAT", true, "未声明版本，发布时按内容自动生成");
        }
        boolean ok = VERSION.matcher(manifest.version()).matches();
        return SkillCheckResult.blocking("VERSION_FORMAT", ok, detail(
                ok, "版本号合法：" + manifest.version(), "版本号格式不合法：" + manifest.version()));
    }

    private static SkillCheckResult kindKnown(SkillManifest manifest) {
        String kind = manifest.kind();
        boolean ok = "script".equalsIgnoreCase(kind) || "agentic".equalsIgnoreCase(kind);
        return SkillCheckResult.blocking("KIND_KNOWN", ok, detail(
                ok,
                "类型合法：" + kind,
                "类型只能写 script 或 agentic（也可以不写，按包内有没有 script 块自动判），当前：" + kind));
    }

    private static SkillCheckResult boundRoutesRegistered(SkillManifest manifest, Map<String, ApiMetadata> apis) {
        // 不写绑定接口不是缺项：绑定有第二个入口（技能包页「绑定接口」），上传只负责把包收下。
        // 写了才校验——写错接口名要当场拦住，否则就成了「绑了三个接口，其实一个都没生效」。
        if (manifest.boundRoutes().isEmpty()) {
            return SkillCheckResult.blocking(
                    "BOUND_APIS_REGISTERED", true, "未声明绑定接口（上传后在技能包页「绑定接口」里配）");
        }
        Set<String> malformed = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (String declared : manifest.boundRoutes()) {
            ApiRoute route = parse(declared, malformed);
            if (route == null) {
                continue;
            }
            ApiMetadata api = apis.get(route.format());
            if (api == null) {
                unknown.add(route.format());
            } else if (!api.enabled()) {
                disabled.add(route.format());
            }
        }
        boolean ok = malformed.isEmpty() && unknown.isEmpty() && disabled.isEmpty();
        return SkillCheckResult.blocking("BOUND_APIS_REGISTERED", ok, detail(
                ok,
                "已绑定 " + manifest.boundRoutes().size() + " 个接口，全部已注册且启用",
                "写法不合法（应为「服务名 方法 路径」）：" + malformed + "；未注册：" + unknown + "；已停用：" + disabled));
    }


    private static SkillCheckResult scriptPolicy(SkillManifest manifest) {
        if (!manifest.isScript()) {
            return SkillCheckResult.blocking("SCRIPT_DATA_POLICY", true, "非脚本技能，不适用");
        }
        SkillManifest.ScriptSpec script = manifest.script();
        if (script == null || script.path() == null) {
            return SkillCheckResult.blocking("SCRIPT_DATA_POLICY", false, "脚本技能必须声明 script.path");
        }
        String policy = script.dataPolicy();
        boolean policyOk = SkillManifest.ScriptSpec.DATA_NONE.equals(policy)
                || SkillManifest.ScriptSpec.DATA_VIA_HOST.equals(policy);
        boolean timeoutOk = script.timeoutSeconds() > 0 && script.timeoutSeconds() <= 300;
        boolean ok = policyOk && timeoutOk;
        return SkillCheckResult.blocking("SCRIPT_DATA_POLICY", ok, detail(
                ok,
                "script.data=" + policy + "、timeoutSeconds=" + script.timeoutSeconds() + "，均在允许范围",
                "script.data 必须是 none / via_host（当前：" + policy + "），timeoutSeconds 必须在 (0,300]（当前："
                        + script.timeoutSeconds() + "）"));
    }

    private static SkillCheckResult scriptPresent(PackageContent content) {
        SkillManifest manifest = content.manifest();
        if (!manifest.isScript()) {
            return SkillCheckResult.blocking("SCRIPT_PRESENT", true, "非脚本技能，不适用");
        }
        String path = manifest.script() == null ? null : manifest.script().path();
        boolean present = content.has(path) && SCRIPT_EXT.matcher(path).matches();
        return SkillCheckResult.blocking("SCRIPT_PRESENT", present, detail(
                present, "脚本在包内：" + path, "包内缺少声明的脚本文件：" + path));
    }

    /** 「导出字段清单评审」的前置自动检查：导出列必须落在绑定接口的**返回字段契约**并集内（§18.4.6 M3）。 */
    private static SkillCheckResult exportsDeclared(SkillManifest manifest, Map<String, ApiMetadata> apis) {
        // 没绑接口就没有「返回字段契约」可作上界，这项检查不适用（绑定后按页面那份重新判定）。
        if (manifest.boundRoutes().isEmpty()) {
            return SkillCheckResult.blocking(
                    "EXPORT_COLUMNS_WHITELISTED", true, "未绑定接口，暂不校验导出列（上界来自绑定接口的返回字段契约）");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String declared : manifest.boundRoutes()) {
            ApiRoute route = parse(declared, null);
            if (route == null) {
                continue;
            }
            ApiMetadata api = apis.get(route.format());
            if (api != null) {
                allowed.addAll(api.resultColumns());
            }
        }
        Set<String> outside = new LinkedHashSet<>();
        for (String column : manifest.exports()) {
            if (!allowed.contains(column)) {
                outside.add(column);
            }
        }
        return SkillCheckResult.blocking(
                "EXPORT_COLUMNS_WHITELISTED",
                outside.isEmpty(),
                detail(outside.isEmpty(),
                        "导出列都在绑定接口的返回字段契约内：" + manifest.exports(),
                        "导出列不在绑定接口的返回字段契约内：" + outside));
    }

    /**
     * 解析绑定项；写法不合法返回 {@code null}（并记进 {@code malformed}）。
     *
     * <p>不在这里直接抛错：一次要看清所有写错的项，而不是改一个跑一次。
     */
    private static ApiRoute parse(String declared, Set<String> malformed) {
        try {
            return ApiRoute.parse(declared);
        } catch (IllegalArgumentException e) {
            if (malformed != null) {
                malformed.add(declared);
            }
            return null;
        }
    }

    private static List<SkillCheckResult> egressHints(PackageContent content) {
        List<SkillCheckResult> results = new ArrayList<>();
        EGRESS_HINTS.forEach((code, pattern) -> {
            Set<String> hits = new LinkedHashSet<>();
            content.files().forEach((name, bytes) -> {
                if (pattern.matcher(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).find()) {
                    hits.add(name);
                }
            });
            results.add(SkillCheckResult.warning(
                    code, hits.isEmpty(), hits.isEmpty() ? "未发现" : "命中文件（仅提示，静态扫描不是安全边界）：" + hits));
        });
        return results;
    }

    /**
     * 绑定接口的元数据视图（只取检查用得到的字段）。
     *
     * <p>这里曾经还有一个 {@code kind} + {@code isWrite()}：用来拦「技能绑写接口」（旧 ADR-19 ②）。
     * 2026-09-27 起不拦了——写操作的治理不靠这一层（网关与接口服务都要求一次性 confirmId，见 §19.9），
     * 少一个字段就少一处"看着像安全边界、其实是配置禁令"的东西。
     */
    public record ApiMetadata(String route, boolean enabled, List<String> resultColumns) {}
}