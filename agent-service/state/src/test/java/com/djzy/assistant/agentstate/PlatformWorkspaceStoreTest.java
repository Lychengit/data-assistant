package com.djzy.assistant.agentstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 多实例共享工作区（H-04）：这里要证的是「两台机器看到同一份工作区文件」。
 *
 * <p>做法是**用同一个库建两个存储对象**——它们之间没有任何内存里的共享，
 * 唯一的联系就是那张表；能互相看见，就说明换一台机器（另起一个进程、连同一个库）也一样能看见。
 * 这比「两个对象是同一个引用」强得多，也比「起两个 Spring 进程」快得多。
 *
 * <p>框架自己的读写实现（{@code JdbcStore}）不在这里重复验，只钉四件平台关心的事：
 * ① 跨实例看得见；② 版本 CAS 拦得住并发抢写；③ 按目录列文件不会串到别的目录；④ 缺表时装配直接失败。
 *
 * <p>用 H2 而不是 PG：表结构是同一套（框架的 H2 方言与 PG 方言语义一致，只有 {@code value_json} 由 TEXT 变 CLOB），
 * 这里验的是共享语义，不是数据库本身；真 PG 上的同款用例见 {@link PlatformWorkspaceStorePgTest}。
 */
class PlatformWorkspaceStoreTest {

    /** 工作区里的一个「目录」。框架按隔离范围自己编命名空间，用例里手写一段就够了。 */
    private static final List<String> DIR = List.of("workspace", "u-1");
    private static final List<String> OTHER_DIR = List.of("workspace", "u-2");

    private JdbcDataSource dataSource;
    /** 甲实例的存储。 */
    private BaseStore instanceA;
    /** 乙实例的存储：跟甲只有「同一个库」这一层关系。 */
    private BaseStore instanceB;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = newDataSource("shared-workspace-" + System.nanoTime());
        createStoreTable(dataSource);
        instanceA = PlatformWorkspaceStore.create(dataSource);
        instanceB = PlatformWorkspaceStore.create(dataSource);
    }

    @Test
    void 甲实例写的文件_乙实例马上读得到() {
        instanceA.put(DIR, "SKILL.md", file("技能说明书"));

        StoreItem seenByB = instanceB.get(DIR, "SKILL.md");

        assertEquals("技能说明书", seenByB.value().get("content"));
    }

    @Test
    void 两台实例同时抢写同一个文件_只有一个赢() {
        instanceA.put(DIR, "SKILL.md", file("第一版"));
        long versionA = instanceA.get(DIR, "SKILL.md").version();
        long versionB = instanceB.get(DIR, "SKILL.md").version();
        assertEquals(versionA, versionB, "两台实例看到的版本号必须一样，否则后面的抢写验不出来");

        assertTrue(instanceA.putIfVersion(DIR, "SKILL.md", file("甲改的"), versionA), "甲先到，应该写成功");
        assertFalse(instanceB.putIfVersion(DIR, "SKILL.md", file("乙改的"), versionB), "乙拿着旧版本写，必须被拒绝");

        assertEquals("甲改的", instanceB.get(DIR, "SKILL.md").value().get("content"), "被拒绝的写不能悄悄落库");
    }

    @Test
    void 文件不存在时按版本0写能建出来_再按版本0写就被挡住() {
        assertTrue(instanceA.putIfVersion(DIR, "新建.md", file("第一次"), 0L));
        assertFalse(instanceA.putIfVersion(DIR, "新建.md", file("又想当新建"), 0L), "它已经存在了，不能再当新建");

        assertEquals("第一次", instanceA.get(DIR, "新建.md").value().get("content"));
    }

    @Test
    void 按目录列文件_不会串到别人的目录里() {
        instanceA.put(DIR, "b.md", file("乙文件"));
        instanceA.put(DIR, "a.md", file("甲文件"));
        instanceA.put(OTHER_DIR, "c.md", file("别人的文件"));

        // 注意实参顺序：框架 JDBC 实现里这两个整数是「limit 在前、offset 在后」（对应 SQL 的 LIMIT ? OFFSET ?），
        // 所以列一整个目录要传 (100, 0)；第一个数为 0 时框架直接返回空列表，不会去查库。
        List<String> keys = instanceB.search(DIR, 100, 0).stream().map(StoreItem::key).toList();

        assertEquals(List.of("a.md", "b.md"), keys, "只该列出本目录的文件，且按名字有序");
    }

    @Test
    void 删掉的文件两个实例都读不到() {
        instanceA.put(DIR, "临时.md", file("用完就删"));

        instanceB.delete(DIR, "临时.md");

        assertNull(instanceA.get(DIR, "临时.md"));
        assertTrue(
                instanceA.search(DIR, 100, 0).stream().noneMatch(item -> item.key().equals("临时.md")),
                "列目录时也不该再出现");
    }

    @Test
    void 缺表时装配直接失败_而不是等到某次对话才炸() {
        JdbcDataSource empty = newDataSource("no-table-" + System.nanoTime());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PlatformWorkspaceStore.create(empty));

        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("agentscope_store"), "报错要说清是哪张表：" + message);
        assertTrue(message.contains("V17__workspace_store.sql"), "报错要直接指向建表脚本：" + message);
    }

    /** 文件内容在存储里就是一份键值对（框架的 BaseStore 契约如此），正文放在 {@code content} 键上。 */
    private static Map<String, Object> file(String content) {
        return Map.of("content", content, "encoding", "utf-8");
    }

    private static JdbcDataSource newDataSource(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        // 用例之间互不干扰：每个用例一个独立的内存库
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** 表由迁移脚本建，不由应用建；用例里就地建一张同口径的表（H2 的 value_json 用 CLOB）。 */
    private static void createStoreTable(JdbcDataSource ds) throws Exception {
        String ddl = "CREATE TABLE agentscope_store ("
                + "  namespace_path VARCHAR(2048) NOT NULL,"
                + "  item_key       VARCHAR(255)  NOT NULL,"
                + "  value_json     CLOB          NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ")";
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }
}
