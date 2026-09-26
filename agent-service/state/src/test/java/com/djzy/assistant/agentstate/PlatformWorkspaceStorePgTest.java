package com.djzy.assistant.agentstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * 真实 PG 上的多实例共享工作区（H-04）。
 *
 * <p>H2 用例证的是语义（{@link PlatformWorkspaceStoreTest}）；这一条补的是「换成真的 PG 还成立吗」，
 * 而且顺手把<strong>迁移脚本本身</strong>验了：建表用的就是 {@code deploy/migrations/V17__workspace_store.sql} 的原文，
 * 不是用例里另抄一份 DDL——脚本写错了这条会红。
 *
 * <p>**默认跳过**：不带 {@code -Dtest.pg.url=...} 时整类跳过（{@code assumeTrue}），
 * 所以 {@code mvn clean install} 不碰真的库；要跑真 PG 时显式给参数，例如：
 * <pre>
 * mvn -pl agent-service/state test -Dtest=PlatformWorkspaceStorePgTest \\
 *     -Dtest.pg.url=jdbc:postgresql://127.0.0.1:5432/doctor_assistant \\
 *     -Dtest.pg.password=******
 * </pre>
 *
 * <p>用例只写自己那一份命名空间（名字里带随机串），跑完自己清干净，不碰库里别人的数据。
 */
class PlatformWorkspaceStorePgTest {

    private static final String URL = System.getProperty("test.pg.url", "");
    private static final String USER = System.getProperty("test.pg.user", "postgres");
    private static final String PASSWORD = System.getProperty("test.pg.password", "");

    /** 本次运行专用的目录，避免与库里其它数据互相看见。 */
    private final List<String> dir = List.of("pg-workspace-test", UUID.randomUUID().toString());
    private final List<String> written = new ArrayList<>();

    private PGSimpleDataSource dataSource;
    private BaseStore store;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(
                !URL.isBlank(), "未指定 -Dtest.pg.url，跳过真实 PG 用例（H2 用例已覆盖同一套语义）");
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        runMigration("V17__workspace_store.sql");
        store = PlatformWorkspaceStore.create(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (store == null) {
            return;
        }
        written.forEach(key -> store.delete(dir, key));
    }

    @Test
    void 迁移脚本建出来的表_两台实例都能用同一份工作区() {
        BaseStore otherInstance = PlatformWorkspaceStore.create(dataSource);
        write("SKILL.md", "真 PG 上的技能说明书");

        assertEquals("真 PG 上的技能说明书", otherInstance.get(dir, "SKILL.md").value().get("content"));
    }

    @Test
    void 真PG上的抢写同样只有一个赢() {
        BaseStore otherInstance = PlatformWorkspaceStore.create(dataSource);
        write("SKILL.md", "第一版");
        long version = store.get(dir, "SKILL.md").version();

        assertTrue(store.putIfVersion(dir, "SKILL.md", file("我改的"), version));
        assertFalse(otherInstance.putIfVersion(dir, "SKILL.md", file("你改的"), version));

        assertEquals("我改的", otherInstance.get(dir, "SKILL.md").value().get("content"));
    }

    private void write(String key, String content) {
        store.put(dir, key, file(content));
        written.add(key);
    }

    private static Map<String, Object> file(String content) {
        return Map.of("content", content, "encoding", "utf-8");
    }

    /**
     * 跑一份迁移脚本：注释行整行丢掉，剩下的按分号切成单条语句逐条执行。
     *
     * <p>脚本里出现的分号全是全角「；」，所以按半角分号切是安全的。
     */
    private void runMigration(String fileName) throws Exception {
        Path script = findRepoRoot().resolve("deploy/migrations").resolve(fileName);
        String body = Files.readString(script);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    /** 从当前工作目录往上找仓库根（认 {@code deploy/migrations} 这个目录），不写死盘符与层级。 */
    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("deploy/migrations"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("找不到仓库根（deploy/migrations）");
        }
        return current;
    }
}
