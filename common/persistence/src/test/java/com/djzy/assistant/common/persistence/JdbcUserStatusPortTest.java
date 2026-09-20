package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcUserStatusPortTest {

    private JdbcUserStatusPort port;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        jdbc.update("INSERT INTO sys_user (id, username, status) VALUES (1,'alice','active'),(2,'carol','disabled')");
        port = new JdbcUserStatusPort(jdbc);
    }

    @Test
    void activeUserIsActive() {
        assertTrue(port.isActive("alice"));
    }

    @Test
    void disabledUserIsInactive() {
        assertFalse(port.isActive("carol"));
    }

    @Test
    void unknownOrBlankUserIsInactive() {
        assertFalse(port.isActive("nobody"));
        assertFalse(port.isActive("  "));
        assertFalse(port.isActive(null));
    }
}
