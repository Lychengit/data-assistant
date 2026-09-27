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
    void registeredCredentialIsPendingAndConsumableExactlyOnce() {
        // 写侧登记（用户点确认时 agent-service 调的就是它）：落一条 pending，网关随后消费
        String confirmId = store.register("alice", "s-9", "t-9", "tool.write", "用户确认执行写操作：iface_doctor_export_upload", null);

        assertTrue(store.find(confirmId).isPresent(), "登记完必须查得到，否则网关认不出这张凭据");
        assertEquals("alice", store.find(confirmId).orElseThrow().userId());
        assertEquals("pending", jdbc.queryForObject(
                "SELECT status FROM pending_confirm WHERE confirm_id = ?", String.class, confirmId));

        assertTrue(store.consume(confirmId, "alice"), "刚登记的凭据必须能被网关消费掉");
        assertFalse(store.consume(confirmId, "alice"), "凭据是一次性的，消费第二次必须失败");
        assertEquals("approved", jdbc.queryForObject(
                "SELECT status FROM pending_confirm WHERE confirm_id = ?", String.class, confirmId));
    }

    @Test
    void registerRefusesWithoutIdentity() {
        // 没有用户或会话的凭据等于一张对谁都有效的通行证，宁可不登记
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.register(null, "s-1", "t-1", "tool.write", "x", null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.register("alice", " ", "t-1", "tool.write", "x", null));
    }

    @Test
    void consumeRejectsExpiredWrongOwnerOrAlreadyResolved() {
        assertFalse(store.consume("c-2", "alice"));
        assertFalse(store.consume("c-3", "alice"));
        assertFalse(store.consume("c-4", "alice"));
        assertFalse(store.consume("c-1", "bob"));
    }
}
