package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcConfirmStoreTest {

    private JdbcConfirmStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PersistenceTestSupport.dataSource();
        jdbc = PersistenceTestSupport.template(dataSource);
        jdbc.update("INSERT INTO pending_confirm (confirm_id, session_id, turn_id, user_id, action, summary, status, expires_at) VALUES "
                + "('c-1','s-1','t-1','alice','perf_report','生成 2026-08 绩效报表','pending', DATEADD('HOUR', 1, now())),"
                + "('c-2','s-1','t-1','alice','perf_report','已过期','pending', DATEADD('HOUR', -1, now())),"
                + "('c-3','s-1','t-1','bob','perf_report','别人的','pending', DATEADD('HOUR', 1, now())),"
                + "('c-4','s-1','t-1','alice','perf_report','已批准','approved', DATEADD('HOUR', 1, now()))");
        store = new JdbcConfirmStore(jdbc);
    }

    @Test
    void findsPendingConfirmation() {
        assertEquals("alice", store.find("c-1").orElseThrow().userId());
        assertTrue(store.find("nope").isEmpty());
    }

    @Test
    void consumeIsOneShot() {
        assertTrue(store.consume("c-1", "alice"));
        assertFalse(store.consume("c-1", "alice"));
        assertEquals("approved", jdbc.queryForObject(
                "SELECT status FROM pending_confirm WHERE confirm_id = 'c-1'", String.class));
    }

    @Test
    void consumeRejectsExpiredWrongOwnerOrAlreadyResolved() {
        assertFalse(store.consume("c-2", "alice"));
        assertFalse(store.consume("c-3", "alice"));
        assertFalse(store.consume("c-4", "alice"));
        assertFalse(store.consume("c-1", "bob"));
    }
}
