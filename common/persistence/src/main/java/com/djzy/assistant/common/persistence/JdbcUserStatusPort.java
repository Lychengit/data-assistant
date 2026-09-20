package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.identity.UserStatusPort;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link UserStatusPort} 的 PG 实现（{@code sys_user.status}）：网关每次请求查一次，
 * 停用 / 离职立即失效（§19.4）。查不到按停用处理（fail-closed）。
 */
public final class JdbcUserStatusPort implements UserStatusPort {

    private static final String STATUS_OF_USER = "SELECT status FROM sys_user WHERE username = ?";

    private final JdbcTemplate jdbc;

    public JdbcUserStatusPort(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcUserStatusPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActive(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        List<String> status = jdbc.queryForList(STATUS_OF_USER, String.class, userId);
        return status.stream().findFirst().map(s -> "active".equalsIgnoreCase(s)).orElse(false);
    }
}
