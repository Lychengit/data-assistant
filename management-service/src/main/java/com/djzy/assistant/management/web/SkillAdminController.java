package com.djzy.assistant.management.web;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.repo.SkillPackageRepository;
import com.djzy.assistant.management.repo.SkillVersionView;
import com.djzy.assistant.management.service.CurrentUserService;
import com.djzy.assistant.management.service.SkillPackageService;
import com.djzy.assistant.management.skill.SkillCheckResult;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * M3 技能包管理入口（§18.4.6 M3）：上传 → 自动检查 → 人工评审 → 发布。
 *
 * <p>管理端配置写直连数据库（§18.9-3），但**只有 admin 能进**（骨架期，§20.4）。
 * 上传走 multipart，包体不落 URL / 不进日志。
 */
@RestController
@RequestMapping("/v1/admin/skill")
public class SkillAdminController {

    private final SkillPackageService skillPackageService;
    private final CurrentUserService currentUserService;

    public SkillAdminController(SkillPackageService skillPackageService, CurrentUserService currentUserService) {
        this.skillPackageService = skillPackageService;
        this.currentUserService = currentUserService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SkillPackageRepository.SkillSummary> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return skillPackageService.listSkills();
    }

    /** 待评审列表：只列「最新包还没发布」的技能（§19.2 只用最新版）。 */
    @GetMapping(path = "/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<VersionBody> pending(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return skillPackageService.listPending().stream().map(VersionBody::of).toList();
    }

    @GetMapping(path = "/{skillCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> detail(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String skillCode) {
        currentUserService.requireAdmin(authorization);
        Map<String, Object> body = new LinkedHashMap<>();
        skillPackageService.listSkills().stream()
                .filter(summary -> summary.skillCode().equals(skillCode))
                .findFirst()
                .ifPresent(summary -> body.put("skill", summary));
        body.put("versions", skillPackageService.listVersions(skillCode).stream().map(VersionBody::of).toList());
        body.put("boundRoutes", skillPackageService.listBoundRoutes(skillCode));
        return body;
    }

    @GetMapping(path = "/versions/{versionId}/checks", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SkillCheckResult> checks(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long versionId) {
        currentUserService.requireAdmin(authorization);
        return skillPackageService.checksOf(versionId);
    }

    /** 上传技能包：返回版本行 + 自动检查明细（blocking 未过会自动驳回，不会挂成待评审）。 */
    @PostMapping(path = "/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        SkillPackageService.UploadResult result =
                skillPackageService.upload(admin.userId(), null, file.getBytes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", VersionBody.of(result.version()));
        body.put("checks", result.checks());
        body.put("duplicateContent", result.duplicateContent());
        return ResponseEntity.ok(body);
    }

    /** 人工评审：批准即发布为不可变版本（同一事务写 skill_api 审核状态 + config_audit）。 */
    @PostMapping(path = "/versions/{versionId}/review", produces = MediaType.APPLICATION_JSON_VALUE)
    public VersionBody review(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long versionId,
            @RequestBody(required = false) ReviewRequest request) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        ReviewRequest safe = request == null ? new ReviewRequest(null, null, null) : request;
        SkillVersionView view = skillPackageService.review(
                versionId,
                Boolean.TRUE.equals(safe.approve()),
                safe.reason(),
                safe.exportedColumns(),
                admin.userId(),
                null);
        return VersionBody.of(view);
    }

    /**
     * 页面改绑：整体替换该技能已审核的绑定接口（{@code skill_api}）。
     *
     * <p>这是「技能 ↔ 接口」的第二条写路径：发布时按包内 manifest 的 {@code boundRoutes} 写入，
     * 之后管理员在「技能包 M3 → 关联接口」里直接改。校验口径与上传时的自动检查逐条一致
     * （接口已注册 + 已启用；写接口同样可绑，见 {@code SkillPackageService#replaceBoundRoutes}），
     * 改动写 {@code config_audit}。
     *
     * <p>为什么不让管理员去改包：改一个接口就要重建包、重算内容哈希、重走上传评审，
     * 实际结果是没人愿意改。副作用是包内容哈希不再能推导出绑定事实，因此审计必须记全量 before/after。
     * 重新发布该技能的包时：包内写了 {@code boundRoutes} 就按它重置，**没写则保持这份不动**。
     */
    @PutMapping(path = "/{skillCode}/apis", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> replaceBoundApis(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String skillCode,
            @RequestBody(required = false) BoundApisRequest request) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        // 缺省（空 body / 无 boundRoutes）= 清空绑定：这是管理员能做的显式动作，不猜成「保持不动」
        List<String> routes = request == null || request.boundRoutes() == null ? List.of() : request.boundRoutes();
        List<String> after = skillPackageService.replaceBoundRoutes(skillCode, routes, admin.userId(), null);
        return Map.of("skillCode", skillCode, "boundRoutes", after, "updated", true);
    }

    @PutMapping(path = "/{skillCode}/enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setEnabled(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String skillCode,
            @RequestBody(required = false) EnabledRequest request) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        boolean enabled = request == null || !Boolean.FALSE.equals(request.enabled());
        boolean updated = skillPackageService.setEnabled(skillCode, enabled, admin.userId(), null);
        return Map.of("skillCode", skillCode, "enabled", enabled, "updated", updated);
    }

    /** 停用（不删包：执行记录里的包哈希还要能查得到）。 */
    @DeleteMapping(path = "/{skillCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> disable(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String skillCode) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        return Map.of("skillCode", skillCode, "updated", skillPackageService.setEnabled(skillCode, false, admin.userId(), null));
    }

    public record ReviewRequest(Boolean approve, String reason, List<String> exportedColumns) {}

    public record EnabledRequest(Boolean enabled) {}

    /** 改绑请求体：{@code ["服务名 方法 路径", ...]} 的完整集合（整体替换，不是增量）。 */
    public record BoundApisRequest(List<String> boundRoutes) {}

    /** 版本对外视图（不暴露 storage_key 之外的内部字段；manifest 原样返回给评审人看）。 */
    public record VersionBody(
            long id,
            String skillCode,
            String version,
            String contentSha256,
            String manifestSha256,
            String kind,
            String status,
            String submittedBy,
            String publishedBy,
            String publishedAt,
            String createdAt) {

        static VersionBody of(SkillVersionView view) {
            return new VersionBody(
                    view.id(),
                    view.skillCode(),
                    view.version(),
                    view.contentSha256(),
                    view.manifestSha256(),
                    view.kind(),
                    view.status(),
                    view.submittedBy(),
                    view.publishedBy(),
                    view.publishedAt() == null ? null : view.publishedAt().toString(),
                    view.createdAt() == null ? null : view.createdAt().toString());
        }
    }
}
