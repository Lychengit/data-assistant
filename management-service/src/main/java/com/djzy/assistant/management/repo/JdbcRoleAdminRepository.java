package com.djzy.assistant.management.repo;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RoleAdminRepository} 的 PG 实现。
 *
 * <p>只读、零缓存（§0.3-4）：新建的角色下一轮问就出现，不用重启管理端。
 */
public final class JdbcRoleAdminRepository implements RoleAdminRepository {

    private static final String LIST = "SELECT role_code, role_name, remark FROM sys_role ORDER BY id";

    private final JdbcTemplate jdbc;

    public JdbcRoleAdminRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcRoleAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoleView> listAll() {
        return jdbc.query(
                LIST,
                (rs, rowNum) ->
                        new RoleView(rs.getString("role_code"), rs.getString("role_name"), rs.getString("remark")));
    }
}
