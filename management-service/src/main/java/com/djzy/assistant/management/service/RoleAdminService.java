package com.djzy.assistant.management.service;

import com.djzy.assistant.management.repo.RoleAdminRepository;
import com.djzy.assistant.management.repo.RoleView;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 角色只读查询（§18.4.6）：给管理端的「角色 × 技能 / 角色 × 接口」两张配置表提供主语。
 *
 * <p>这里是纯读，**不写 {@code config_audit}**：审计记的是"配置被改了什么"，
 * 把下拉框拉一次也记一条只会把真正的变更淹掉。读侧留痕另有其人（§20.4 的 {@code audit_read_audit}）。
 */
@Service
public class RoleAdminService {

    private final RoleAdminRepository repository;

    public RoleAdminService(RoleAdminRepository repository) {
        this.repository = repository;
    }

    public List<RoleView> list() {
        return repository.listAll();
    }
}
