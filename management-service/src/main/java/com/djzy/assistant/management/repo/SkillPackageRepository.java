package com.djzy.assistant.management.repo;

import com.djzy.assistant.management.skill.SkillCheckResult;
import com.djzy.assistant.management.skill.SkillPackageInspector;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能包与版本的读写（§18.4.6 M3 / §19.2）。
 *
 * <p>发布动作是**一个事务**：写版本状态 + upsert {@code sys_skill} + 写 {@code skill_api.approved}
 * + 写 {@code config_audit}（§20.7：审计写失败则变更回滚）。
 */
public interface SkillPackageRepository {

    Optional<SkillVersionView> findByContentHash(String contentSha256);

    Optional<SkillVersionView> findVersion(long versionId);

    Optional<SkillVersionView> findLatest(String skillCode);

    List<SkillVersionView> listVersions(String skillCode);

    /** 待评审（最新版尚未发布）的技能包。 */
    List<SkillVersionView> listPending();

    List<SkillSummary> listSkills();

    long insertVersion(VersionDraft draft);

    void updateStatus(long versionId, String status, String publishedBy, Instant publishedAt);

    void insertChecks(long versionId, List<SkillCheckResult> checks, Instant createdAt);

    List<SkillCheckResult> listChecks(long versionId);

    void insertReview(long versionId, String decision, String reviewer, String reason, List<String> exports, Instant createdAt);

    /** 绑定接口元数据（自动检查用）。 */
    Map<String, SkillPackageInspector.ApiMetadata> apiMetadata(List<String> routes);

    /** 发布：upsert 技能主表 + 把绑定接口标为已审核（ADR-19 发布时一次审核）。 */
    void publishSkill(String skillCode, String name, String description, String paramSchemaJson, List<String> routes, String publishedBy, Instant publishedAt);

    boolean setSkillEnabled(String skillCode, boolean enabled);

    /** @return 技能主表当前状态（不存在返回空） */
    Optional<SkillSummary> findSkill(String skillCode);

    record VersionDraft(
            String skillCode,
            String version,
            String contentSha256,
            String storageKey,
            String manifestJson,
            String manifestSha256,
            String kind,
            String submittedBy,
            Instant createdAt) {}

    record SkillSummary(
            String skillCode,
            String name,
            String description,
            String status,
            String latestVersion,
            String latestStatus,
            Instant updatedAt) {}
}
