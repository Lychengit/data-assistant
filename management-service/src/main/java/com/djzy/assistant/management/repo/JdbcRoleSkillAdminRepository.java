package com.djzy.assistant.management.repo;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RoleSkillAdminRepository} 的 PG 实现。
 *
 * <p>{@code role_skill} 是三列关联表（{@code role_id, skill_id, can_view}）：
 * 与 {@code role_api} 不同，它多一个「可见性」开关——不可见的技能不进网关下发的能力集，
 * 也就不会出现在模型的工作区里（§4.7）。
 */
public final class JdbcRoleSkillAdminRepository implements RoleSkillAdminRepository {

    private static final String COLUMNS =
            "r.role_code, s.id AS skill_id, s.skill_code, s.name AS skill_name, rs.can_view";

    private static final String JOINS = """
              FROM role_skill rs
              JOIN sys_role  r ON r.id = rs.role_id
              JOIN sys_skill s ON s.id = rs.skill_id
            """;

    private static final String LIST =
            "SELECT " + COLUMNS + JOINS + " WHERE r.role_code = ? ORDER BY s.skill_code";

    private static final String FIND =
            "SELECT " + COLUMNS + JOINS + " WHERE r.role_code = ? AND s.skill_code = ?";

    private final JdbcTemplate jdbc;
    private final String roleIdSql;
    private final String skillIdSql;
    private final String insertSql;
    private final String updateSql;
    private final String deleteSql;

    public JdbcRoleSkillAdminRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcRoleSkillAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.roleIdSql = "SELECT id FROM sys_role WHERE role_code = ?";
        this.skillIdSql = "SELECT id FROM sys_skill WHERE skill_code = ?";
        this.insertSql = "INSERT INTO role_skill (role_id, skill_id, can_view) VALUES (?, ?, ?)";
        this.updateSql = "UPDATE role_skill SET can_view = ? WHERE role_id = ? AND skill_id = ?";
        this.deleteSql = "DELETE FROM role_skill WHERE role_id = ? AND skill_id = ?";
    }

    @Override
    public List<RoleSkillGrantView> listByRole(String roleCode) {
        return jdbc.query(LIST, (rs, rowNum) -> view(rs), roleCode);
    }

    @Override
    public Optional<RoleSkillGrantView> find(String roleCode, String skillCode) {
        return jdbc.query(FIND, (rs, rowNum) -> view(rs), roleCode, skillCode).stream().findFirst();
    }

    @Override
    public void grant(String roleCode, String skillCode, boolean canView) {
        long roleId = requireId(roleIdSql, roleCode, "角色不存在：" + roleCode);
        long skillId = requireId(skillIdSql, skillCode, "技能不存在：" + skillCode);
        if (jdbc.update(updateSql, canView, roleId, skillId) == 0) {
            jdbc.update(insertSql, roleId, skillId, canView);
        }
    }

    @Override
    public boolean revoke(String roleCode, String skillCode) {
        Optional<Long> roleId = findId(roleIdSql, roleCode);
        Optional<Long> skillId = findId(skillIdSql, skillCode);
        if (roleId.isEmpty() || skillId.isEmpty()) {
            return false;
        }
        return jdbc.update(deleteSql, roleId.get(), skillId.get()) > 0;
    }

    private static RoleSkillGrantView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoleSkillGrantView(
                rs.getString("role_code"),
                rs.getLong("skill_id"),
                rs.getString("skill_code"),
                rs.getString("skill_name"),
                rs.getBoolean("can_view"));
    }

    private long requireId(String sql, String code, String message) {
        return findId(sql, code).orElseThrow(() -> new IllegalArgumentException(message));
    }

    private Optional<Long> findId(String sql, String code) {
        return jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), code).stream().findFirst();
    }
}