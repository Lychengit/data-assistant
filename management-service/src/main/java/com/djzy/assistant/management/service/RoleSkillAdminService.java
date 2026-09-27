package com.djzy.assistant.management.service;

import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.management.repo.RoleSkillAdminRepository;
import com.djzy.assistant.management.repo.RoleSkillGrantView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色/技能授权配置（§4.7 / §18.4.6 / §20.7）——「哪些角色能用哪些技能」就落在这张表上。
 *
 * <p>三条口径与 {@link RoleApiAdminService} 完全一致：
 * <ul>
 *   <li>**授权就是一张三列关联表**：{@code role_skill(role_id, skill_id, can_view)}，
 *       没有额外字段——技能里调得到什么由 {@code skill_api} 声明，且**随这一行一起授出**，
 *       不是第二条授权链（§4.6 并集，ADR-19）；</li>
 *   <li>**改动立即生效**：零缓存直查 PG（§0.3-4）；下一轮对话网关就会按新授权下发技能；</li>
 *   <li>**改动本身要记账**：一次变更一条 {@code config_audit}，与变更**同一事务**，
 *       审计失败则变更回滚（不允许「改了但没记账」）。</li>
 * </ul>
 */
@Service
public class RoleSkillAdminService {

    private static final String TARGET = "role";

    private final RoleSkillAdminRepository repository;
    private final ConfigAuditWriter configAuditWriter;

    public RoleSkillAdminService(RoleSkillAdminRepository repository, ConfigAuditWriter configAuditWriter) {
        this.repository = repository;
        this.configAuditWriter = configAuditWriter;
    }

    public List<RoleSkillGrantView> list(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalArgumentException("缺少角色编码");
        }
        return repository.listByRole(roleCode.trim());
    }

    /**
     * 给角色授权某个技能（已授权则只更新可见性；没变化就不写审计，避免噪音）。
     *
     * @throws IllegalArgumentException 角色或技能不存在
     */
    @Transactional
    public RoleSkillGrantView grant(String roleCode, String skillCode, boolean canView, String who, String requestId) {
        String role = requireText(roleCode, "角色编码");
        String skill = requireText(skillCode, "技能编码");
        RoleSkillGrantView before = repository.find(role, skill).orElse(null);
        repository.grant(role, skill, canView);
        RoleSkillGrantView after = repository.find(role, skill).orElseThrow();
        if (before == null || before.canView() != after.canView()) {
            audit(who, field(role, after.display()), before == null ? null : before.toAuditMap(), after.toAuditMap(), requestId);
        }
        return after;
    }

    /** 撤销授权（删除整行）：撤销后该角色既不"可见"也不"可用"这个技能（§4.7）。 */
    @Transactional
    public boolean revoke(String roleCode, String skillCode, String who, String requestId) {
        String role = requireText(roleCode, "角色编码");
        String skill = requireText(skillCode, "技能编码");
        RoleSkillGrantView before = repository.find(role, skill).orElse(null);
        boolean deleted = repository.revoke(role, skill);
        if (deleted && before != null) {
            audit(who, field(role, before.display()), before.toAuditMap(), null, requestId);
        }
        return deleted;
    }

    private void audit(
            String who, String field, Map<String, Object> before, Map<String, Object> after, String requestId) {
        configAuditWriter.write(new ConfigAuditEntry(who, TARGET, field, before, after, requestId, Instant.now()));
    }

    private static String field(String roleCode, String display) {
        return "role_skill:" + roleCode + ":" + display;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
        return value.trim();
    }
}