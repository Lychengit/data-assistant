package com.djzy.assistant.agentstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentStateStore;
import java.sql.Connection;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 会话档案（H-13）：标题 / 提问条数 / 最后提问时刻这三个「列表要的数」怎么维护。
 *
 * <p>为什么值得单独一组用例：这三个字段是**列表的唯一数据来源**（列表不再回读对话正文），
 * 一旦它们记错，用户看到的就是「标题是别人的话」「条数对不上」这种一眼可见的错。
 *
 * <p>这里只钉口径，不重复验框架的存取能力（那是 {@link PlatformAgentStateStoreTest} 的事）：
 * ① 第一句定标题、之后只加条数；② 标题压平 + 截断；③ 档案缺失时先补一份再记；
 * ④ 提问不碰归档标记；⑤ 空提问照样算一次，但不会把标题写成空。
 */
class PlatformSessionStoreTest {

    private JdbcDataSource dataSource;
    private PlatformSessionStore metaStore;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        // 每个用例一个独立库：用例之间不互相看见对方的档案
        dataSource.setURL("jdbc:h2:mem:platform-session-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        createSessionStateTable();
        metaStore = new PlatformSessionStore(PlatformAgentStateStore.create(dataSource));
    }

    @Test
    void 第一句定标题_之后只加条数并推进最后提问时刻() {
        metaStore.create("u-1", "s-1", 1000L);

        metaStore.recordQuestion("u-1", "s-1", "心内科门诊量", 2000L);
        metaStore.recordQuestion("u-1", "s-1", "那外科呢", 3000L);

        PlatformSessionState meta = metaStore.load("u-1", "s-1").orElseThrow();
        // 标题只认第一句：第二句不改标题，否则列表标题会随着用户后面聊什么一直跳
        assertEquals("心内科门诊量", meta.getTitle());
        assertEquals(2, meta.getQuestions());
        assertEquals(3000L, meta.getLastActiveMs());
        // 建档时刻是建档那一刻的，不因为提问被改写
        assertEquals(1000L, meta.getCreatedAtMs());
    }

    @Test
    void 标题压平空白并截断_不会把超长一句话原样存进去() {
        metaStore.recordQuestion("u-1", "s-1", "  第一行\n第二行   很长的一段   话  ", 2000L);

        PlatformSessionState meta = metaStore.load("u-1", "s-1").orElseThrow();
        assertEquals("第一行 第二行 很长的一段 话", meta.getTitle());
    }

    @Test
    void 标题超过上限就截断并加省略号() {
        String longQuestion = "问".repeat(PlatformSessionState.TITLE_MAX + 20);

        String title = PlatformSessionState.truncate(longQuestion);

        assertEquals(PlatformSessionState.TITLE_MAX + 1, title.length());
        assertTrue(title.endsWith("…"));
    }

    @Test
    void 档案不存在时先补一份再记_老会话不会丢掉这次记录() {
        // 不先 create：模拟「升级前建的老会话只有对话、没有档案」
        metaStore.recordQuestion("u-1", "s-1", "老会话的第一句", 5000L);

        PlatformSessionState meta = metaStore.load("u-1", "s-1").orElseThrow();
        assertEquals("老会话的第一句", meta.getTitle());
        assertEquals(1, meta.getQuestions());
        // 补档案时建档时刻取「现在」：我们不知道它原来什么时候建的，编一个反而更误导
        assertEquals(5000L, meta.getCreatedAtMs());
    }

    @Test
    void 空提问照样算一次_但不会把标题写成空() {
        metaStore.recordQuestion("u-1", "s-1", "   ", 2000L);

        PlatformSessionState meta = metaStore.load("u-1", "s-1").orElseThrow();
        assertNull(meta.getTitle());
        assertEquals(1, meta.getQuestions());
        // 档案里就老实留空：显示成「未命名会话」是列表展示层的事（那条口径在 web 模块的用例里守）
    }

    @Test
    void 提问不碰归档标记_归档过的会话聊一句还是归档着的() {
        metaStore.setArchived("u-1", "s-1", true, 1500L);

        metaStore.recordQuestion("u-1", "s-1", "再问一句", 2000L);

        PlatformSessionState meta = metaStore.load("u-1", "s-1").orElseThrow();
        assertTrue(meta.isArchived());
        assertEquals(1500L, meta.getArchivedAtMs());
        assertFalse(meta.getTitle().isBlank());
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
}
