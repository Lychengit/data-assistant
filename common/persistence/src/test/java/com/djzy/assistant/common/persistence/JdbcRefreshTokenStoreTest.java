package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 刷新令牌（§19.4）：只存哈希、一次性消费、过期与登出即失效。 */
class JdbcRefreshTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-19T02:00:00Z");

    @Test
    void consumesOnceAndRejectsReplay() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcRefreshTokenStore store = new JdbcRefreshTokenStore(jdbc);
        store.save("hash-1", "alice", NOW.plusSeconds(3600));

        assertEquals("alice", store.consume("hash-1", NOW).orElseThrow());
        assertTrue(store.consume("hash-1", NOW).isEmpty());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM refresh_token WHERE token_hash = 'hash-1'");
        assertEquals("alice", row.get("user_id"));
        assertEquals(NOW, ((java.sql.Timestamp) row.get("revoked_at")).toInstant());
    }

    @Test
    void rejectsExpiredAndRevokedTokens() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        JdbcRefreshTokenStore store = new JdbcRefreshTokenStore(jdbc);
        store.save("hash-expired", "bob", NOW.minusSeconds(1));
        store.save("hash-revoked", "bob", NOW.plusSeconds(3600));
        store.revoke("hash-revoked", NOW);

        assertTrue(store.consume("hash-expired", NOW).isEmpty());
        assertTrue(store.consume("hash-revoked", NOW).isEmpty());
        assertTrue(store.consume("hash-unknown", NOW).isEmpty());
        assertTrue(store.consume(null, NOW).isEmpty());
    }
}
