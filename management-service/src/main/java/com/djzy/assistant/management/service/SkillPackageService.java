package com.djzy.assistant.management.service;

import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.common.skill.PackageContent;
import com.djzy.assistant.common.skill.PackageReader;
import com.djzy.assistant.common.skill.SkillManifest;
import com.djzy.assistant.management.repo.SkillPackageRepository;
import com.djzy.assistant.management.repo.SkillVersionView;
import com.djzy.assistant.management.skill.SkillCheckResult;
import com.djzy.assistant.management.skill.SkillPackageInspector;
import com.djzy.assistant.management.skill.SkillPackageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M3 技能包管理（§18.4.6 M3 / §18.5.2 / §19.2）：上传 → 自动检查 → 人工评审 → 发布成**不可变版本**。
 *
 * <p>三条硬口径：
 * <ul>
 *   <li>**内容寻址**：同一个包（内容哈希相同）重复上传不产生新版本行，也不重跑检查——内容没变，结论就不会变；
 *   <li>**只用最新版**：只有该技能的最新包可以被评审/发布，旧包不许「补发」（§19.2 不做版本并存）；
 *   <li>**审计与变更同事务**：发布/驳回/停用各写一条 {@code config_audit}，审计失败则变更回滚（§20.7）。
 * </ul>
 *
 * <p>自动检查里的 blocking 失败会**自动驳回**（写成一条 reviewer=system 的评审记录），
 * 不留「检查没过但挂在待评审列表里」的中间态。
 */
@Service
public class SkillPackageService {

    private static final String TARGET = "skill";
    private static final String SYSTEM_REVIEWER = "system";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillPackageRepository repository;
    private final SkillPackageStore store;
    private final ConfigAuditWriter configAuditWriter;

    public SkillPackageService(
            SkillPackageRepository repository, SkillPackageStore store, ConfigAuditWriter configAuditWriter) {
        this.repository = repository;
        this.store = store;
        this.configAuditWriter = configAuditWriter;
    }

    public List<SkillPackageRepository.SkillSummary> listSkills() {
        return repository.listSkills();
    }

    public List<SkillVersionView> listVersions(String skillCode) {
        requireText(skillCode, "技能编码");
        return repository.listVersions(skillCode);
    }

    /** 该技能已审核的绑定接口（发布评审的产物），供管理端读回。 */
    public List<String> listBoundRoutes(String skillCode) {
        requireText(skillCode, "技能编码");
        return repository.listBoundRoutes(skillCode);
    }

    /**
     * 页面改绑（管理端「技能包 M3 → 绑定接口」）：把该技能已审核的绑定接口**整体替换**成 {@code routes}。
     *
     * <p>为什么要有它：绑定此前只有 manifest 一个来源——只为换掉一个接口，就要重建包、重算内容哈希、
     * 重走上传与评审，代价高到没人愿意改，绑定只会烂在原地。因此改绑是**发布之外的第二条写路径**；
     * 代价是包内容哈希不再能推导出绑定事实，所以这里把生效值（before/after 完整集合）记进 {@code config_audit}。
     *
     * <p>校验口径与上传时的自动检查**逐条一致**：写法合法、接口已在 {@code sys_api} 注册且启用。
     * 写接口不再被拦（2026-09-27 起旧 ADR-19 ② 取消）：真正管住写操作的是网关与接口服务各自要求的
     * 一次性 {@code confirmId}（§19.9），不是"配置上不许绑"——禁令只是把该配的东西逼到别处去配。
     * 重新发布该技能的包时，绑定按包内 manifest 重置——发布始终是不可变版本的唯一入口。
     *
     * @param routes 规范三元组文本（{@code 服务名 方法 路径}），重复项自动去重
     * @return 落库后的实际绑定（**读回**，不拿入参当事实）
     */
    @Transactional
    public List<String> replaceBoundRoutes(String skillCode, List<String> routes, String who, String requestId) {
        requireText(skillCode, "技能编码");
        String code = skillCode.trim();
        if (repository.findSkill(code).isEmpty()) {
            throw new IllegalArgumentException("技能不存在：" + code);
        }
        List<String> declared = normalizeRoutes(routes);
        Map<String, SkillPackageInspector.ApiMetadata> apis = repository.apiMetadata(declared);
        List<String> unknown = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (String route : declared) {
            SkillPackageInspector.ApiMetadata api = apis.get(route);
            if (api == null) {
                unknown.add(route);
            } else if (!api.enabled()) {
                disabled.add(route);
            }
        }
        if (!unknown.isEmpty() || !disabled.isEmpty()) {
            // fail-closed：一处不合法就整条拒绝，不做「合法的先绑上」——半套绑定比不改更难查
            throw new IllegalArgumentException("绑定接口不合法（未注册：" + unknown + "；已停用：" + disabled + "）");
        }
        List<String> before = repository.listBoundRoutes(code);
        repository.replaceBoundRoutes(code, declared, who, Instant.now());
        List<String> after = repository.listBoundRoutes(code);
        if (!before.equals(after)) {
            audit(
                    who,
                    requestId,
                    "skill_api:" + code,
                    Map.<String, Object>of("boundRoutes", before),
                    Map.<String, Object>of("boundRoutes", after));
        }
        return after;
    }

    /** 规范三元组：解析 → 归一（方法大写）→ 去重；写法不合法整条报错，不猜。 */
    private static List<String> normalizeRoutes(List<String> routes) {
        if (routes == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String route : routes) {
            normalized.add(ApiRoute.parse(route).format());
        }
        return List.copyOf(normalized);
    }

    public List<SkillVersionView> listPending() {
        return repository.listPending();
    }

    public List<SkillCheckResult> checksOf(long versionId) {
        return repository.listChecks(versionId);
    }

    public SkillVersionView requireVersion(long versionId) {
        return repository.findVersion(versionId).orElseThrow(() -> new IllegalArgumentException("技能包版本不存在"));
    }

    /**
     * 上传技能包：读包 → 算内容哈希 → 落内容寻址存储 → 自动检查 → 落版本与检查明细。
     *
     * @return 版本行 + 检查结论 + 是否命中已有内容（内容相同即同一对象，§19.2）
     */
    @Transactional
    public UploadResult upload(String who, String requestId, byte[] packageBytes) {
        PackageContent content = PackageReader.read(packageBytes);

        var existing = repository.findByContentHash(content.sha256());
        if (existing.isPresent()) {
            SkillVersionView view = existing.get();
            return new UploadResult(view, repository.listChecks(view.id()), true);
        }

        String storageKey = store.store(content.sha256(), packageBytes);
        SkillManifest manifest = content.manifest();
        Instant now = Instant.now();
        long versionId = repository.insertVersion(new SkillPackageRepository.VersionDraft(
                manifest.skillCode(),
                manifest.version() == null ? autoVersion(content.sha256()) : manifest.version(),
                content.sha256(),
                storageKey,
                content.manifestJson(),
                content.manifestSha256(),
                manifest.kind(),
                who,
                now));

        Map<String, SkillPackageInspector.ApiMetadata> apis = repository.apiMetadata(manifest.boundRoutes());
        List<SkillCheckResult> checks = SkillPackageInspector.inspect(content, apis);
        repository.insertChecks(versionId, checks, now);

        List<String> blockingFailures = checks.stream()
                .filter(SkillCheckResult::blockingFailure)
                .map(SkillCheckResult::code)
                .toList();
        if (blockingFailures.isEmpty()) {
            repository.updateStatus(versionId, "checked", null, null);
        } else {
            repository.updateStatus(versionId, "rejected", SYSTEM_REVIEWER, null);
            repository.insertReview(
                    versionId,
                    "rejected",
                    SYSTEM_REVIEWER,
                    "自动检查未通过：" + String.join(", ", blockingFailures),
                    manifest.exports(),
                    now);
        }
        audit(who, requestId, "skill_package:" + manifest.skillCode(), null, versionAudit(versionId, content.sha256(), null));
        SkillVersionView view = repository.findVersion(versionId).orElseThrow();
        return new UploadResult(view, checks, false);
    }

    /**
     * 人工评审（§18.4.6 M3）：通过 → 发布成不可变版本；驳回 → 记原因。
     *
     * @param exportedColumns 评审人核过的导出字段清单（存证用；自动检查只是前置过滤）
     */
    @Transactional
    public SkillVersionView review(
            long versionId,
            boolean approve,
            String reason,
            List<String> exportedColumns,
            String who,
            String requestId) {
        SkillVersionView version = requireVersion(versionId);
        SkillVersionView latest = repository.findLatest(version.skillCode()).orElse(version);
        if (latest.id() != version.id()) {
            // 传了旧版本 id 是**调用方用错了**（评审对象永远是最新包），按 400 回，不冒充 500。
            throw new IllegalArgumentException("只能评审该技能的最新包（§19.2 不做版本并存）");
        }
        Map<String, Object> rawManifest = parseJson(version.manifestJson());
        SkillManifest manifest = SkillManifest.from(rawManifest);
        Instant now = Instant.now();
        if (!approve) {
            repository.updateStatus(versionId, "rejected", who, null);
            repository.insertReview(
                    versionId, "rejected", who, reason, exportedColumns == null ? manifest.exports() : exportedColumns, now);
            audit(who, requestId, "skill_package:" + version.skillCode(), versionAudit(versionId, version.contentSha256(), "checked"), versionAudit(versionId, version.contentSha256(), "rejected"));
            return repository.findVersion(versionId).orElseThrow();
        }
        List<String> blockingFailures = repository.listChecks(versionId).stream()
                .filter(SkillCheckResult::blockingFailure)
                .map(SkillCheckResult::code)
                .toList();
        if (!blockingFailures.isEmpty()) {
            throw new IllegalStateException("自动检查未通过，不得发布：" + String.join(", ", blockingFailures));
        }
        // 包内没写 boundRoutes 就**不动**库里那份绑定（页面「绑定接口」配的），写空数组 [] 才是清空。
        // 两者必须分开：上传者的包里通常不提接口，若按「空 = 清空」处理，一次重新发布就会把管理员
        // 在页面上一条条勾出来的绑定静默抹掉——那种故障只能靠对比权限表现才发现。
        repository.publishSkill(
                version.skillCode(),
                manifest.name(),
                manifest.description(),
                toJson(manifest.paramSchema()),
                manifest.declaresBoundRoutes() ? manifest.boundRoutes() : null,
                who,
                now);
        repository.updateStatus(versionId, "published", who, now);
        repository.insertReview(
                versionId, "approved", who, reason, exportedColumns == null ? manifest.exports() : exportedColumns, now);
        audit(who, requestId, "skill_package:" + version.skillCode(), versionAudit(versionId, version.contentSha256(), "checked"), versionAudit(versionId, version.contentSha256(), "published"));
        return repository.findVersion(versionId).orElseThrow();
    }

    /** 停用技能（不删包：历史执行记录里的包哈希还要能查得到，§19.2 可追溯）。 */
    @Transactional
    public boolean setEnabled(String skillCode, boolean enabled, String who, String requestId) {
        requireText(skillCode, "技能编码");
        var before = repository.findSkill(skillCode).orElse(null);
        boolean updated = repository.setSkillEnabled(skillCode, enabled);
        if (updated) {
            Map<String, Object> beforeMap =
                    before == null ? null : Map.<String, Object>of("status", String.valueOf(before.status()));
            audit(
                    who,
                    requestId,
                    "skill:" + skillCode,
                    beforeMap,
                    Map.<String, Object>of("status", enabled ? "active" : "disabled"));
        }
        return updated;
    }

    private void audit(String who, String requestId, String field, Map<String, Object> before, Map<String, Object> after) {
        configAuditWriter.write(new ConfigAuditEntry(who, TARGET, field, before, after, requestId, Instant.now()));
    }

    private static Map<String, Object> versionAudit(long versionId, String contentSha256, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("versionId", versionId);
        map.put("contentSha256", contentSha256);
        map.put("status", status);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("manifest 无法解析", e);
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 未写版本时的自动版本：{@code 0.0-<内容哈希前 8 位>}。
     *
     * <p>为什么用内容哈希而不是时间戳：版本行的唯一键是 {@code (skill_code, version)}，时间戳在同一秒里
     * 连传两个不同的包就会撞唯一键（对上传播只是 500），而内容哈希天然一一对应——同一份内容本来就
     * 只会有一个版本行（内容寻址），自动版本跟着它走就不可能撞。
     */
    private static String autoVersion(String contentSha256) {
        return "0.0-" + contentSha256.substring(0, 8);
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
    }

    /** 上传结果（含是否命中已有内容与自动检查明细）。 */
    public record UploadResult(SkillVersionView version, List<SkillCheckResult> checks, boolean duplicateContent) {}
}
