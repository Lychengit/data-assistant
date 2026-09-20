package com.djzy.assistant.management.service;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiKind;
import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.management.repo.ApiAdminRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M4 接口注册（§18.4.6 / §20.7）。
 *
 * <p>登记的是「接口是什么」：{@link ApiRoute 三元组}（服务 + 方法 + 路径）、副作用等级、适用场景、
 * 入参契约、返回字段契约。
 *
 * <p>三条 fail-closed 约束：
 * <ul>
 *   <li>**路径形态受控**：{@link ApiRoute} 的白名单正则拒掉 {@code ..} / {@code //} / {@code ?}，
 *       因为这些字符会由网关拼进下游 URL——放过它们等于把路径穿越的口子留给"能改注册表的人"；
 *   <li>**跨服务同名路径禁登记**：模型侧工具名由路径派生（{@code /doctor/performance} → {@code iface_doctor_performance}），
 *       两个服务登记同一方法+路径就会撞出同一个工具名，模型只看得见其中一个（另一个静默消失）。宁可不注册；
 *   <li>**已被角色授权或被技能绑定的接口不许删**：删除会让既有授权悬空，必须走「停用」或先撤销授权（可追溯）。
 * </ul>
 *
 * <p>**没有列白名单**：返回哪些列由接口自己的 SQL 与返回类型写死（比运行时白名单更硬，多返回一列在编译期就不可能）。
 */
@Service
public class ApiAdminService {

    private static final String TARGET = "api";

    /** 服务名：小写字母开头，允许数字与连字符（docker-compose 服务名 / 内网 DNS，§20.3）。 */
    private static final Pattern SERVICE = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    private final ApiAdminRepository repository;
    private final ConfigAuditWriter configAuditWriter;
    private final JdbcTemplate jdbc;

    public ApiAdminService(
            ApiAdminRepository repository, ConfigAuditWriter configAuditWriter, JdbcTemplate jdbc) {
        this.repository = repository;
        this.configAuditWriter = configAuditWriter;
        this.jdbc = jdbc;
    }

    public List<ApiDescriptor> list() {
        return repository.list();
    }

    public ApiDescriptor get(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("接口未注册：#" + id));
    }

    /**
     * 登记或更新接口。新增与更新都各写一条 {@code config_audit}（带 before/after），与变更同一事务。
     *
     * @throws IllegalArgumentException 服务名 / 路径不合法、方法与路径已被别的服务占用，或 {@code kind} 不是 read/write
     */
    @Transactional
    public ApiDescriptor upsert(
            String service,
            String httpMethod,
            String httpPath,
            String name,
            String kind,
            String resource,
            Map<String, Object> paramSchema,
            Boolean enabled,
            String scenario,
            Map<String, Object> resultSchema,
            String who,
            String requestId) {
        ApiDescriptor descriptor = validate(
                service, httpMethod, httpPath, name, kind, resource, paramSchema, enabled, scenario, resultSchema);
        ApiDescriptor before = repository.findByRoute(descriptor.route()).orElse(null);
        if (before == null) {
            requireRouteNotTaken(descriptor);
        }
        repository.upsert(descriptor);
        ApiDescriptor after = repository.findByRoute(descriptor.route()).orElseThrow();
        audit(
                who,
                field(after.route()),
                before == null ? null : toAuditMap(before),
                toAuditMap(after),
                requestId);
        return after;
    }

    /** 启用 / 停用：停用后网关解析不到目标服务 → 调用被拒（§20.3）；状态未变化则不写审计（避免噪音）。 */
    @Transactional
    public ApiDescriptor setEnabled(long id, boolean enabled, String who, String requestId) {
        ApiDescriptor before = get(id);
        if (before.enabled() == enabled) {
            return before;
        }
        requireUpdated(repository.setEnabled(id, enabled), id);
        ApiDescriptor after = get(id);
        audit(who, field(after.route()), toAuditMap(before), toAuditMap(after), requestId);
        return after;
    }

    /** 删除登记（仅限从未被授权、也未被技能绑定的接口）。 */
    @Transactional
    public boolean delete(long id, String who, String requestId) {
        ApiDescriptor before = repository.findById(id).orElse(null);
        if (before == null) {
            return false;
        }
        requireUnreferenced(id);
        requireUpdated(repository.delete(id), id);
        audit(who, field(before.route()), toAuditMap(before), null, requestId);
        return true;
    }

    /**
     * 方法与路径不得被别的服务占用。
     *
     * <p>判据只看「方法 + 路径」：模型侧工具名正是由路径派生（{@link ApiRoute#toolName()}），
     * 服务名不进工具名。两个服务占同一路径时模型只看得见其中一个，另一个静默消失——比报错难查得多。
     */
    private void requireRouteNotTaken(ApiDescriptor descriptor) {
        Long taken = jdbc.queryForObject(
                "SELECT count(*) FROM sys_api WHERE upper(http_method) = ? AND http_path = ? AND service <> ?",
                Long.class,
                descriptor.httpMethod(),
                descriptor.httpPath(),
                descriptor.service());
        if (taken != null && taken > 0) {
            throw new IllegalArgumentException(
                    "方法 + 路径已被别的服务占用，工具名会撞车（模型只能看见其中一个）：" + descriptor.route().display());
        }
    }

    /** 被授权或被技能绑定的接口不许删：授权悬空比「接口多停用一个」危险得多（fail-closed）。 */
    private void requireUnreferenced(long id) {
        Long grants = jdbc.queryForObject("SELECT count(*) FROM role_api WHERE api_id = ?", Long.class, id);
        Long bindings = jdbc.queryForObject("SELECT count(*) FROM skill_api WHERE api_id = ?", Long.class, id);
        if ((grants != null && grants > 0) || (bindings != null && bindings > 0)) {
            throw new IllegalArgumentException("接口已被角色授权或技能绑定，请改为停用或先撤销授权：#" + id);
        }
    }

    private ApiDescriptor validate(
            String service,
            String httpMethod,
            String httpPath,
            String name,
            String kind,
            String resource,
            Map<String, Object> paramSchema,
            Boolean enabled,
            String scenario,
            Map<String, Object> resultSchema) {
        String svc = requireText(service, "所属服务");
        if (!SERVICE.matcher(svc).matches()) {
            throw new IllegalArgumentException("服务名不合法（小写字母开头，数字/连字符）：" + svc);
        }
        String method = requireText(httpMethod, "HTTP 方法");
        String path = requireText(httpPath, "接口路径");
        // 交给 ApiRoute 构造器做形态校验：路径穿越字符在这里就被拒，不会留到网关拼接时才发作
        ApiRoute route = new ApiRoute(svc, method, path);
        String displayName = requireText(name, "接口名称");
        if (displayName.length() > 128) {
            throw new IllegalArgumentException("接口名称过长（≤128）：" + displayName);
        }
        ApiKind apiKind = parseKind(kind, route);
        return new ApiDescriptor(
                0L,
                displayName,
                route.service(),
                route.httpMethod(),
                route.httpPath(),
                apiKind,
                resource == null || resource.isBlank() ? null : resource.trim(),
                paramSchema == null ? Map.of() : paramSchema,
                enabled == null || enabled,
                scenario,
                resultSchema == null ? Map.of() : resultSchema);
    }

    private static ApiKind parseKind(String kind, ApiRoute route) {
        if (kind == null || kind.isBlank()) {
            return ApiKind.READ;
        }
        return switch (kind.trim().toLowerCase()) {
            case "read" -> ApiKind.READ;
            case "write" -> ApiKind.WRITE;
            default -> throw new IllegalArgumentException("副作用等级只允许 read/write：" + route.display() + " → " + kind);
        };
    }

    private void requireUpdated(boolean updated, long id) {
        if (!updated) {
            throw new IllegalArgumentException("接口未注册：#" + id);
        }
    }

    private void audit(String who, String field, Map<String, Object> before, Map<String, Object> after, String requestId) {
        configAuditWriter.write(new ConfigAuditEntry(who, TARGET, field, before, after, requestId, Instant.now()));
    }

    /** 审计快照用**规范化结构**（kind 展开成小写字符串），便于逐字节比对。 */
    private static Map<String, Object> toAuditMap(ApiDescriptor descriptor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", descriptor.id());
        map.put("service", descriptor.service());
        map.put("httpMethod", descriptor.httpMethod());
        map.put("httpPath", descriptor.httpPath());
        map.put("name", descriptor.name());
        map.put("kind", descriptor.kind().name().toLowerCase());
        map.put("resource", descriptor.resource());
        map.put("paramSchema", descriptor.paramSchema());
        map.put("enabled", descriptor.enabled());
        map.put("scenario", descriptor.scenario());
        map.put("resultSchema", descriptor.resultSchema());
        return map;
    }

    private static String field(ApiRoute route) {
        return "sys_api:" + route.format();
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
        return value.trim();
    }
}