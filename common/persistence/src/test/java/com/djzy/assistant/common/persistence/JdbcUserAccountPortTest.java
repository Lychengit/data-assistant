package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.identity.PasswordHasher;
import com.djzy.assistant.common.identity.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 账号读取（M1）：口令只在登录时比对，读取结果不脱敏但调用方不得外泄（§18.4.6）。 */
class JdbcUserAccountPortTest {

    @Test
    void findsAccountAndVerifiesPassword() {
        javax.sql.DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        String hash = PasswordHasher.hash("admin123");
        jdbc.update(
                "INSERT INTO sys_user (id, username, password_hash, display_name, status) VALUES (?, ?, ?, ?, ?)",
                1L, "admin", hash, "系统管理员", "active");
        jdbc.update(
                "INSERT INTO sys_user (id, username, password_hash, display_name, status) VALUES (?, ?, ?, ?, ?)",
                2L, "gone", hash, "已停用", "disabled");

        JdbcUserAccountPort port = new JdbcUserAccountPort(jdbc);

        UserAccount admin = port.findByUsername("admin").orElseThrow();
        assertEquals("admin", admin.userId());
        assertEquals("系统管理员", admin.displayName());
        assertTrue(admin.active());
        assertTrue(PasswordHasher.verify("admin123", admin.passwordHash()));

        assertFalse(port.findByUsername("gone").orElseThrow().active());
        assertEquals("admin", port.findById("admin").orElseThrow().username());
        assertFalse(port.findById("gone").orElseThrow().active());
        assertTrue(port.findById("nobody").isEmpty());
        assertTrue(port.findByUsername("nobody").isEmpty());
        assertTrue(port.findByUsername(null).isEmpty());
    }
}
