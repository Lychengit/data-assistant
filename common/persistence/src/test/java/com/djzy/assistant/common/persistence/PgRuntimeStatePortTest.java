package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.Snapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 运行时状态落 PG（§19.5 / §19.14）：按三元组寻址、覆盖写、跨运行时快照原样取回。 */
class PgRuntimeStatePortTest {

    private static Snapshot snapshot(String runtimeId, int turns) {
        return new Snapshot(runtimeId, "2.0.3", "s-1", 1_760_000_000_000L, Map.of("turns", turns, "pendingTool", "doctor_performance"));
    }

    private static PgRuntimeStatePort port() {
        return new PgRuntimeStatePort(PersistenceTestSupport.template(PersistenceTestSupport.dataSource()), false);
    }

    @Test
    void 没写过就没有_不是空快照() {
        assertTrue(port().load("u-1", "s-none", "turn").isEmpty());
    }

    @Test
    void 存了能原样取回_含运行时标识与版本() {
        PgRuntimeStatePort port = port();
        port.save("u-1", "s-1", "turn", snapshot("noop", 3));

        Snapshot loaded = port.load("u-1", "s-1", "turn").orElseThrow();
        assertEquals("noop", loaded.runtimeId());
        assertEquals("2.0.3", loaded.runtimeVersion());
        assertEquals("s-1", loaded.sessionId());
        assertEquals(1_760_000_000_000L, loaded.createdAtEpochMs());
        assertEquals(3, loaded.payload().get("turns"));
        assertEquals("doctor_performance", loaded.payload().get("pendingTool"));
    }

    @Test
    void 同一段的再次保存是覆盖_不是追加() {
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        PgRuntimeStatePort port = new PgRuntimeStatePort(jdbc, false);

        port.save("u-1", "s-1", "turn", snapshot("noop", 1));
        port.save("u-1", "s-1", "turn", snapshot("noop", 2));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_state", Integer.class));
        assertEquals(2, port.load("u-1", "s-1", "turn").orElseThrow().payload().get("turns"));
    }

    @Test
    void 三元组寻址_用户与会话不会串门() {
        PgRuntimeStatePort port = port();
        port.save("u-1", "s-1", "turn", snapshot("noop", 1));
        port.save("u-2", "s-1", "turn", snapshot("noop", 2));
        port.save("u-1", "s-2", "turn", snapshot("noop", 3));
        port.save("u-1", "s-1", "other", snapshot("noop", 4));

        assertEquals(1, port.load("u-1", "s-1", "turn").orElseThrow().payload().get("turns"));
        assertEquals(2, port.load("u-2", "s-1", "turn").orElseThrow().payload().get("turns"));
        assertEquals(3, port.load("u-1", "s-2", "turn").orElseThrow().payload().get("turns"));
        assertEquals(4, port.load("u-1", "s-1", "other").orElseThrow().payload().get("turns"));
    }

    @Test
    void 删除后读不到_但只删这一段() {
        PgRuntimeStatePort port = port();
        port.save("u-1", "s-1", "turn", snapshot("noop", 1));
        port.save("u-1", "s-1", "other", snapshot("noop", 2));

        port.delete("u-1", "s-1", "turn");

        assertTrue(port.load("u-1", "s-1", "turn").isEmpty());
        assertTrue(port.load("u-1", "s-1", "other").isPresent());
    }

    @Test
    void 空载荷也存得住_恢复时不当成脏数据() {
        PgRuntimeStatePort port = port();
        port.save("u-1", "s-1", "turn", new Snapshot("noop", "2.0.3", "s-1", 1L, Map.of()));

        assertTrue(port.load("u-1", "s-1", "turn").orElseThrow().payload().isEmpty());
    }

    @Test
    void 换一个实例照样读得到_状态不跟进程走() {
        // 模拟「换 pod」：两个实例各自 new（不共享任何内存），只有库是同一份
        JdbcTemplate jdbc = PersistenceTestSupport.template(PersistenceTestSupport.dataSource());
        new PgRuntimeStatePort(jdbc, false).save("u-1", "s-1", "turn", snapshot("noop", 5));

        Snapshot loaded = new PgRuntimeStatePort(jdbc, false).load("u-1", "s-1", "turn").orElseThrow();
        assertEquals(5, loaded.payload().get("turns"));
    }

    @Test
    void 会话下能列出状态段_便于清理与巡检() {
        PgRuntimeStatePort port = port();
        port.save("u-1", "s-1", "turn", snapshot("noop", 1));
        port.save("u-1", "s-1", "tool", snapshot("noop", 2));

        assertEquals(List.of("tool", "turn"), port.keysOf("u-1", "s-1"));
        assertFalse(port.keysOf("u-1", "s-9").iterator().hasNext());
    }

    @Test
    void 跨运行时的快照原样返回_拒绝恢复的判定不在这里做() {
        // §19.14：不允许跨运行时恢复。端口只负责如实取回 runtime_id，
        // 由运行时在 resume 时比对并抛 RuntimeMismatchException（这里存 noop，换 agentscope 来读也能读到）。
        PgRuntimeStatePort port = port();
        port.save("u-1", "s-1", "turn", snapshot("noop", 1));

        assertEquals("noop", port.load("u-1", "s-1", "turn").orElseThrow().runtimeId());
    }
}
