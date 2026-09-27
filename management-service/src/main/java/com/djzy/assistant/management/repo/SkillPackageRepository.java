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

    /**
     * 技能已审核的绑定接口（{@code skill_api} join {@code sys_api}），形如
     * {@code "interface-doctor POST /doctor/list"}，按 {@code sys_api.id} 稳定排序。
     *
     * <p>为什么要有它：绑定关系是「技能能用哪些接口」的唯一事实，评审通过后它落在 {@code skill_api} 里，
     * 但此前**没有任何读回入口**——包传上去了、接口绑上了，管理端却看不到绑的是哪几个。
     */
    List<String> listBoundRoutes(String skillCode);

    /**
     * 页面改绑：把该技能在 {@code skill_api} 里的绑定**整体替换**成 {@code routes}（先删后插，同一事务）。
     *
     * <p>为什么允许发布之外的第二条写路径：manifest 是包作者写的，改一个接口就要重建包、重算内容哈希、
     * 重走上传与评审——代价高到没人愿意改，绑定只会烂在原地。所以管理端把「技能能调哪些接口」做成可配项。
     * 代价是内容哈希不再能推导出绑定事实，因此改绑必须写 {@code config_audit}（before/after 都是完整集合）。
     *
     * <p>「整体替换」而不是增量：绑定在语义上是**一份集合**，增量会让声明与库里的行悄悄漂移，
     * 而能力集（{@code apiSet(user)}）正是按集合算的。
     *
     * @param routes 规范三元组文本（{@code 服务名 方法 路径}），调用方已校验存在/启用/非写接口
     */
    void replaceBoundRoutes(String skillCode, List<String> routes, String approvedBy, Instant approvedAt);

    /**
     * 发布：upsert 技能主表 + 把绑定接口标为已审核（ADR-19 发布时一次审核）。
     *
     * @param routes {@code null} = 包内**没写** {@code boundRoutes}，保留库里已有绑定（页面配的那份）；
     *               非 {@code null}（含空列表）= 按这个集合整体替换
     */
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
