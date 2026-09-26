package com.djzy.assistant.agentstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 平台会话状态存储（T1-13）：验的是「框架的实现 + 我们补的那一处」合起来的行为。
 *
 * <p>框架自己的 CAS / 列表 / 按用户列会话由框架保障，这里不重复验；这里只钉三件事：
 * ① 按 key 删除真的删掉了（**这是框架漏掉、我们补的那一处**）；
 * ② 只删这一段，同会话的其它状态与其它会话不受影响；
 * ③ 匿名用户（{@code userId == null}）也能删——这一条同时钉住槽位号的拼法：
 * 拼错了删除就会落空，这条用例会红，而不是等到线上「删除静默失效」。
 *
 * <p>用 H2 而不是 PG：表结构是同一套（框架的 H2 方言与 PG 方言列名列序一致），
 * 用例要验的是删除语义，不是数据库本身。
 */
class PlatformAgentStateStoreTest {

    private static final String KEY_A = "platform_turn";
    private static final String KEY_B = "agent_state";

    private JdbcDataSource dataSource;
    private AgentStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        // 每个用例一个独立库：用例之间不互相看见对方的状态
        dataSource.setURL("jdbc:h2:mem:platform-state-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        createSessionStateTable();
        store = PlatformAgentStateStore.create(dataSource);
    }

    @Test
    void 按key删除只删这一段_同会话的其它状态还在() {
        store.save("u-1", "s-1", KEY_A, new Note("轮次快照"));
        store.save("u-1", "s-1", KEY_B, new Note("会话正文"));

        store.delete("u-1", "s-1", KEY_A);

        assertTrue(store.get("u-1", "s-1", KEY_A, Note.class).isEmpty());
        assertTrue(store.get("u-1", "s-1", KEY_B, Note.class).isPresent());
    }

    @Test
    void 删一个会话的状态不影响别的会话() {
        store.save("u-1", "s-1", KEY_A, new Note("甲"));
        store.save("u-1", "s-2", KEY_A, new Note("乙"));

        store.delete("u-1", "s-1", KEY_A);

        assertTrue(store.get("u-1", "s-1", KEY_A, Note.class).isEmpty());
        assertTrue(store.get("u-1", "s-2", KEY_A, Note.class).isPresent());
    }

    @Test
    void 匿名用户也能按key删除_这条同时钉住槽位号的拼法() {
        store.save(null, "s-1", KEY_A, new Note("匿名用户的状态"));

        store.delete(null, "s-1", KEY_A);

        assertTrue(store.get(null, "s-1", KEY_A, Note.class).isEmpty());
    }

    @Test
    void 版本CAS由框架实现_创建与冲突的行为保持原样() {
        // 第一次按「还不存在」写：拿到版本 1
        assertEquals(1L, store.saveIfVersion("u-1", "s-1", KEY_A, new Note("第一版"), 0L));
        // 再按「还不存在」写：冲突，什么都不写（这就是 CAS 底线）
        assertEquals(
                AgentStateStore.UNVERSIONED,
                store.saveIfVersion("u-1", "s-1", KEY_A, new Note("第二版"), 0L));
        // 按正确版本写：成功并推进版本
        assertEquals(2L, store.saveIfVersion("u-1", "s-1", KEY_A, new Note("第二版"), 1L));

        assertEquals("第二版", store.get("u-1", "s-1", KEY_A, Note.class).orElseThrow().getText());
    }

    @Test
    void 按用户列会话仍然可用() {
        store.save("u-1", "s-1", KEY_A, new Note("甲"));
        store.save("u-1", "s-2", KEY_A, new Note("乙"));
        store.save("u-2", "s-9", KEY_A, new Note("丙"));

        assertEquals(Set.of("s-1", "s-2"), store.listSessionIds("u-1"));
        assertEquals(Set.of("s-9"), store.listSessionIds("u-2"));
        assertFalse(store.listSessionIds("u-3").contains("s-1"));
    }

    /** 表由迁移脚本建，不由应用建；用例里就地建一张同口径的表（H2 的 state_data 用 CLOB）。 */
    private void createSessionStateTable() throws Exception {
        String ddl = "CREATE TABLE agentscope_sessions ("
                + "  session_id  VARCHAR(255) NOT NULL,"
                + "  state_key   VARCHAR(255) NOT NULL,"
                + "  item_index  INT          NOT NULL DEFAULT 0,"
                + "  state_data  CLOB         NOT NULL,"
                + "  version     BIGINT       NOT NULL DEFAULT 0,"
                + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                + "  PRIMARY KEY (session_id, state_key, item_index)"
                + ")";
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    /** State 是个标记接口，随便一个能被 JSON 编解码的类就能当状态用。 */
    public static final class Note implements State {

        private String text;

        public Note() {}

        Note(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
