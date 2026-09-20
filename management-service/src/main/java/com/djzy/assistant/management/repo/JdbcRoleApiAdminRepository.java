package com.djzy.assistant.management.repo;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RoleApiAdminRepository} 的 PG 实现。
 *
 * <p>{@code role_api} 现在是**纯关联表**（{@code role_id, api_id}）：授权只说"能不能调"，
 * 不说"能看多大范围"——范围由接口服务基于登录人推导（§19.1）。
 */
public final class JdbcRoleApiAdminRepository implements RoleApiAdminRepository {

    private static final String COLUMNS = "r.role_code, a.id AS api_id, a.http_method, a.http_path";

    private static final String JOINS = """
              FROM role_api ra
              JOIN sys_role r ON r.id = ra.role_id
              JOIN sys_api  a ON a.id = ra.api_id
            """;

    private static final String LIST =
            "SELECT " + COLUMNS + JOINS + " WHERE r.role_code = ? ORDER BY a.service, a.http_path";

    private static final String FIND = "SELECT " + COLUMNS + JOINS + " WHERE r.role_code = ? AND a.id = ?";

    private final JdbcTemplate jdbc;
    private final String roleIdSql;
    private final String apiExistsSql;
    private final String existsSql;
    private final String insertSql;
    private final String deleteSql;

    public JdbcRoleApiAdminRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcRoleApiAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.roleIdSql = "SELECT id FROM sys_role WHERE role_code = ?";
        this.apiExistsSql = "SELECT count(*) FROM sys_api WHERE id = ?";
        this.existsSql = "SELECT count(*) FROM role_api WHERE role_id = ? AND api_id = ?";
        this.insertSql = "INSERT INTO role_api (role_id, api_id) VALUES (?, ?)";
        this.deleteSql = "DELETE FROM role_api WHERE role_id = ? AND api_id = ?";
    }

    @Override
    public List<RoleApiGrantView> listByRole(String roleCode) {
        return jdbc.query(LIST, (rs, rowNum) -> view(rs), roleCode);
    }

    @Override
    public Optional<RoleApiGrantView> find(String roleCode, long apiId) {
        return jdbc.query(FIND, (rs, rowNum) -> view(rs), roleCode, apiId).stream().findFirst();
    }

    @Override
    public void grant(String roleCode, long apiId) {
        long roleId = requireId(roleIdSql, roleCode, "角色不存在：" + roleCode);
        Long exists = jdbc.queryForObject(apiExistsSql, Long.class, apiId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("接口未注册：#" + apiId);
        }
        Long granted = jdbc.queryForObject(existsSql, Long.class, roleId, apiId);
        if (granted != null && granted > 0) {
            return;
        }
        jdbc.update(insertSql, roleId, apiId);
    }

    @Override
    public boolean delete(String roleCode, long apiId) {
        Optional<Long> roleId = findId(roleIdSql, roleCode);
        if (roleId.isEmpty()) {
            return false;
        }
        return jdbc.update(deleteSql, roleId.get(), apiId) > 0;
    }

    private static RoleApiGrantView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoleApiGrantView(
                rs.getString("role_code"), rs.getLong("api_id"), rs.getString("http_method"), rs.getString("http_path"));
    }

    private long requireId(String sql, String code, String message) {
        return findId(sql, code).orElseThrow(() -> new IllegalArgumentException(message));
    }

    private Optional<Long> findId(String sql, String code) {
        return jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), code).stream().findFirst();
    }
}