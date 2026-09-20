package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.identity.UserAccount;
import com.djzy.assistant.common.identity.UserAccountPort;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link UserAccountPort} 的 PG 实现（{@code sys_user}，§4.2）。 */
public final class JdbcUserAccountPort implements UserAccountPort {

    /** 口径：{@code userId} = {@code sys_user.username}（与权限库、用户状态查询一致，§4.9）。 */
    private static final String FIND = """
            SELECT username, password_hash, display_name, status
              FROM sys_user
             WHERE username = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcUserAccountPort(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcUserAccountPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return first(jdbc.query(FIND, JdbcUserAccountPort::mapRow, username));
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        return findByUsername(userId);
    }

    private static UserAccount mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String username = rs.getString("username");
        return new UserAccount(
                username,
                username,
                rs.getString("password_hash"),
                rs.getString("display_name"),
                "active".equalsIgnoreCase(rs.getString("status")));
    }

    private static Optional<UserAccount> first(List<UserAccount> rows) {
        return rows.stream().findFirst();
    }
}
