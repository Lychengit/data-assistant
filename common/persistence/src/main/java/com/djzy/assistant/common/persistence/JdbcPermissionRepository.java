package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.permission.PermissionRepository;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link PermissionRepository} 的 PG 实现（零缓存直查，§0.3-4）。
 *
 * <p>只取数、不判定：并集公式与 fail-closed 语义仍由 {@code AuthorizationService} 唯一实现（§4.9）。
 *
 * <p>口径：{@code userId} = {@code sys_user.username}（与 {@code permission_audit.user_id VARCHAR(64)} 一致，
 * 也让审计日志里出现的是可读的用户标识）；{@code roleId} = {@code sys_role.role_code}；
 * 接口一律用 {@code sys_api.id} 表示（授权比对的键，见 {@link PermissionRepository} 的说明）。
 */
public final class JdbcPermissionRepository implements PermissionRepository {

    private static final String ROLES_OF_USER = """
            SELECT r.role_code
              FROM sys_user_role ur
              JOIN sys_user u ON u.id = ur.user_id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE u.username = ?
             ORDER BY r.role_code
            """;

    private static final String SKILLS_OF_ROLES_PREFIX = """
            SELECT DISTINCT s.skill_code
              FROM role_skill rs
              JOIN sys_role r ON r.id = rs.role_id
              JOIN sys_skill s ON s.id = rs.skill_id
             WHERE rs.can_view = TRUE AND r.role_code IN (
            """;

    private static final String PERSONAL_SKILLS = """
            SELECT DISTINCT s.skill_code
              FROM sys_skill s
              JOIN sys_user u ON u.id = s.owner_id
             WHERE s.owner_type = 'personal' AND u.username = ?
             ORDER BY s.skill_code
            """;

    private static final String APPROVED_APIS_OF_SKILL = """
            SELECT DISTINCT a.id
              FROM skill_api sa
              JOIN sys_skill s ON s.id = sa.skill_id
              JOIN sys_api a ON a.id = sa.api_id
             WHERE s.skill_code = ? AND sa.approved = TRUE AND a.enabled = TRUE
             ORDER BY a.id
            """;

    private static final String DIRECT_APIS_OF_ROLES_PREFIX = """
            SELECT DISTINCT a.id
              FROM role_api ra
              JOIN sys_role r ON r.id = ra.role_id
              JOIN sys_api a ON a.id = ra.api_id
             WHERE a.enabled = TRUE AND r.role_code IN (
            """;

    private final JdbcTemplate jdbc;

    public JdbcPermissionRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcPermissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> roleIdsOf(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        return jdbc.queryForList(ROLES_OF_USER, String.class, userId);
    }

    @Override
    public List<String> skillCodesOfRoles(List<String> roleIds) {
        if (isEmpty(roleIds)) {
            return List.of();
        }
        String sql = SKILLS_OF_ROLES_PREFIX + placeholders(roleIds.size()) + ") ORDER BY s.skill_code";
        return jdbc.queryForList(sql, String.class, roleIds.toArray());
    }

    @Override
    public List<String> personalSkillCodesOf(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        return jdbc.queryForList(PERSONAL_SKILLS, String.class, userId);
    }

    @Override
    public List<Long> approvedApiIdsOfSkill(String skillCode) {
        if (isBlank(skillCode)) {
            return List.of();
        }
        return jdbc.queryForList(APPROVED_APIS_OF_SKILL, Long.class, skillCode);
    }

    @Override
    public List<Long> directApiIdsOfRoles(List<String> roleIds) {
        if (isEmpty(roleIds)) {
            return List.of();
        }
        String sql = DIRECT_APIS_OF_ROLES_PREFIX + placeholders(roleIds.size()) + ") ORDER BY a.id";
        return jdbc.queryForList(sql, Long.class, roleIds.toArray());
    }

    static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}