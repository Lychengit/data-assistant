package com.djzy.assistant.management.service;

import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.management.repo.RoleApiAdminRepository;
import com.djzy.assistant.management.repo.RoleApiGrantView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2 角色/接口授权配置（§18.4.6 / §20.7）。
 *
 * <p>三条口径：
 * <ul>
 *   <li>**授权只回答"能不能调"**：接口用 {@code api_id} 指代，不配数据范围——范围由接口服务
 *       基于登录人自己推导（§19.1）。权限库因此完全不认识业务表结构，接口变复杂也不用动它；
 *   <li>**改动立即生效**：零缓存直查 PG（§0.3-4），不存在缓存漂移；
 *   <li>**改动本身要记账**：一次变更一条 {@code config_audit}（谁 / 何时 / 从什么 / 改成什么），
 *       与变更**同一事务**，审计失败则变更回滚（不允许「改了但没记账」）。
 * </ul>
 */
@Service
public class RoleApiAdminService {

    private static final String TARGET = "role";

    private final RoleApiAdminRepository repository;
    private final ConfigAuditWriter configAuditWriter;

    public RoleApiAdminService(RoleApiAdminRepository repository, ConfigAuditWriter configAuditWriter) {
        this.repository = repository;
        this.configAuditWriter = configAuditWriter;
    }

    public List<RoleApiGrantView> list(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalArgumentException("缺少角色编码");
        }
        return repository.listByRole(roleCode.trim());
    }

    /**
     * 给角色授权某个接口（已授权则无操作，不写审计避免噪音）。
     *
     * @throws IllegalArgumentException 角色或接口不存在
     */
    @Transactional
    public RoleApiGrantView grant(String roleCode, long apiId, String who, String requestId) {
        String role = requireText(roleCode, "角色编码");
        RoleApiGrantView before = repository.find(role, apiId).orElse(null);
        repository.grant(role, apiId);
        RoleApiGrantView after = repository.find(role, apiId).orElseThrow();
        if (before == null) {
            audit(who, field(role, after.display()), null, after.toAuditMap(), requestId);
        }
        return after;
    }

    /** 撤销授权（删除整行）：撤销后该角色对该接口不再有权限，网关判定侧 403（§19.7）。 */
    @Transactional
    public boolean revoke(String roleCode, long apiId, String who, String requestId) {
        String role = requireText(roleCode, "角色编码");
        RoleApiGrantView before = repository.find(role, apiId).orElse(null);
        boolean deleted = repository.delete(role, apiId);
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
        return "role_api:" + roleCode + ":" + display;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
        return value.trim();
    }
}