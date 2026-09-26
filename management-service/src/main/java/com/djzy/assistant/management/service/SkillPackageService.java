package com.djzy.assistant.management.service;

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
import java.util.LinkedHashMap;
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
                manifest.version(),
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
        SkillManifest manifest = SkillManifest.from(parseJson(version.manifestJson()));
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
        repository.publishSkill(
                version.skillCode(),
                manifest.name(),
                manifest.description(),
                toJson(manifest.paramSchema()),
                manifest.boundRoutes(),
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

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
    }

    /** 上传结果（含是否命中已有内容与自动检查明细）。 */
    public record UploadResult(SkillVersionView version, List<SkillCheckResult> checks, boolean duplicateContent) {}
}
