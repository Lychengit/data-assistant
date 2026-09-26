package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.agentstate.PlatformAgentStateStore;
import com.djzy.assistant.agentstate.PlatformWorkspaceStore;
import com.djzy.assistant.runtime.agentscope.AgentscopeRuntimeAdapter;
import com.djzy.assistant.runtime.agentscope.ModelProvider;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.tool.ToolCatalog;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 多实例共享工作区（H-04）：把「两台实例看到同一份工作区文件」这件事，**从真实的 HarnessAgent 上**验一遍。
 *
 * <p>{@code PlatformWorkspaceStoreTest} 验的是存储本身；这里补的是<strong>接线</strong>：
 * 装了 {@code RemoteFilesystemSpec} 的 agent，它的文件系统真的读写了那张共享表；没装的时候还是本机的。
 * 接线错了（比如 {@code filesystem(...)} 传了却没生效）在存储用例里看不出来——那条路照样全绿。
 *
 * <p>两台实例 = 「两个适配器 + 各自一个存储对象 + 各自一个本地工作目录，连同一个库」：
 * 它们之间没有共享任何内存，唯一的联系就是那张表。
 *
 * <p>隔离范围是 {@code USER}，所以用例顺便钉住两件事：同一个用户换个会话仍看得见同一份，
 * 换个用户则看不见。技能按人隔离（H-06）就架在这个粒度上。
 *
 * <p><b>框架自己还有一道闸</b>：装 {@code RemoteFilesystemSpec} 时，如果会话状态是本进程内的实现
 * （{@code JsonFileAgentStateStore} / {@code InMemoryAgentStateStore}），{@code build()} 会直接抛错——
 * 「文件共享了、状态各是各的」是框架明确拒绝的组合。所以这个用例里的会话状态用的是生产同一个实现，
 * 并且单有一条用例把这个规矩钉住。
 *
 * <p>放在 web 模块、而且包名与生产类一致，都是刻意的：一来要用到 {@code agent-service/state} 的存储实现
 * （web 同时依赖两边），二来 {@code AgentscopeSession} 是包内可见的，测试要够得着它。
 */
class SharedWorkspaceTest {

    /** 本测试不跑推理，只看「文件放哪儿」；模型工厂给个永不会被调用的空实现就够。 */
    private static final ModelProvider NO_MODEL = request -> null;

    private static final String SKILL_PATH = "skills/demo/SKILL.md";
    private static final String SKILL_BODY = "# 演示技能\n按这个步骤做。";

    private JdbcDataSource dataSource;
    private AgentStateStore stateStore;
    private BaseStore storeForA;
    private BaseStore storeForB;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:shared-workspace-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        createTables();
        // 会话状态也必须是「分布式」的：框架在装共享工作区时会检查这一点（见下面那条用例），
        // 所以这里用的是生产同一个实现（JDBC 状态库 + H2 方言）。
        stateStore = PlatformAgentStateStore.create(dataSource);
        storeForA = PlatformWorkspaceStore.create(dataSource);
        storeForB = PlatformWorkspaceStore.create(dataSource);
    }

    @Test
    void 甲实例写下的技能文件_乙实例读得到() {
        AbstractFilesystem fsA = filesystem(adapter(storeForA, workspaceDir("a")), "u-1", "s-1");
        AbstractFilesystem fsB = filesystem(adapter(storeForB, workspaceDir("b")), "u-1", "s-1");

        assertTrue(fsA.write(ctx("u-1", "s-1"), SKILL_PATH, SKILL_BODY).isSuccess());

        ReadResult readByB = fsB.read(ctx("u-1", "s-1"), SKILL_PATH, 0, 0);
        assertTrue(readByB.isSuccess(), () -> "乙实例该读到甲实例写的文件，实际失败：" + readByB.error());
        assertEquals(SKILL_BODY, readByB.fileData().content());
    }

    @Test
    void 同一个用户的另一个会话也看得见_换的是会话不是人() {
        AbstractFilesystem fsA = filesystem(adapter(storeForA, workspaceDir("a")), "u-1", "s-1");
        AbstractFilesystem fsB = filesystem(adapter(storeForB, workspaceDir("b")), "u-1", "s-2");

        assertTrue(fsA.write(ctx("u-1", "s-1"), SKILL_PATH, SKILL_BODY).isSuccess());

        assertTrue(
                fsB.read(ctx("u-1", "s-2"), SKILL_PATH, 0, 0).isSuccess(),
                "隔离粒度是人不是会话：同一个人的另一个会话也该看得见");
    }

    @Test
    void 别人的技能看不见() {
        AbstractFilesystem fsA = filesystem(adapter(storeForA, workspaceDir("a")), "u-1", "s-1");
        AbstractFilesystem fsB = filesystem(adapter(storeForB, workspaceDir("b")), "u-2", "s-9");

        assertTrue(fsA.write(ctx("u-1", "s-1"), SKILL_PATH, SKILL_BODY).isSuccess());

        assertFalse(fsB.read(ctx("u-2", "s-9"), SKILL_PATH, 0, 0).isSuccess(), "别人不该读到我的工作区文件");
    }

    @Test
    void 没开共享工作区时_不是远端文件系统() {
        // 传 null 的 store 就等于 agent-service.workspace-store=none：文件只在本机。
        // 这里不断言「具体是哪种本地实现」——那是框架自己的事，平台只关心「没有被接到共享存储上」。
        AbstractFilesystem local = filesystem(adapter(null, workspaceDir("local-fs")), "u-1", "s-1");

        assertFalse(local instanceof RemoteFilesystem, "开关没开时不该悄悄接上共享存储");
    }

    @Test
    void 会话状态是本机实现时_框架不许接共享工作区() {
        // 这是框架自己的规矩：工作区共享了、会话状态却只在本进程里，多副本就会「文件是同一份、状态各是各的」，
        // 于是它干脆拒绝装配。这条用例把这个规矩钉住——将来谁想把状态库换成本地实现，会在这里被拦住。
        AgentscopeRuntimeAdapter localState = new AgentscopeRuntimeAdapter(NO_MODEL, null, workspaceDir("local-state"), storeForA);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> filesystem(localState, "u-1", "s-1"));

        assertTrue(
                String.valueOf(failure.getMessage()).contains("distributed"),
                "报错要说清是「缺分布式会话状态」：" + failure.getMessage());
    }

    @Test
    void 技能下发钩子拿得到这一轮可见的技能清单() {
        // 装了下发（agent-service.workspace-skills=workspace）时，每轮开始前平台要把它同步进工作区。
        // 这里验的是「接线」：清单从请求里到了钩子上，没掉在半路。
        List<List<String>> seen = new ArrayList<>();
        AgentscopeRuntimeAdapter adapter = new AgentscopeRuntimeAdapter(
                NO_MODEL, stateStore, workspaceDir("provision"), storeForA, (fs, context, skills) -> seen.add(skills));

        agent(adapter, "u-1", "s-1", List.of("doctor_income"));

        assertEquals(List.of(List.of("doctor_income")), seen, "钩子该拿到这一轮网关给的技能清单");
    }

    @Test
    void 没装技能下发时_技能读取工具不在工具面里() {
        // 没装下发时工作区里没有任何技能，那个「读技能说明」的工具必须一起关掉：
        // 留着一个永远读不到东西的工具，模型只会拿它瞎试；而且它绕开平台的 ToolInvoker，是多开的一个口子。
        AgentscopeRuntimeAdapter adapter =
                new AgentscopeRuntimeAdapter(NO_MODEL, stateStore, workspaceDir("no-skills"), storeForA);

        assertFalse(
                agent(adapter, "u-1", "s-1", List.of())
                        .getToolkit()
                        .getToolNames()
                        .contains("load_skill_through_path"),
                "没装技能下发就不该放行技能读取工具");
    }

    private static RuntimeContext ctx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    /** 从真实 agent 上拿它的工作区文件系统——这正是「接线生效了没有」的观察点。 */
    private static AbstractFilesystem filesystem(
            AgentscopeRuntimeAdapter adapter, String userId, String sessionId) {
        return agent(adapter, userId, sessionId, List.of()).getWorkspaceManager().getFilesystem();
    }

    /** 起一个会话、拿到这次装配出来的那个共享 agent（技能工具面就挂在它上面）。 */
    private static HarnessAgent agent(
            AgentscopeRuntimeAdapter adapter, String userId, String sessionId, List<String> visibleSkills) {
        AgentRunRequest request = AgentRunRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .requestId("r-1")
                .tools(ToolCatalog.empty())
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 10_000)
                .maxIters(1)
                .visibleSkills(visibleSkills)
                .build();
        AgentscopeRuntimeAdapter.AgentscopeSession session =
                (AgentscopeRuntimeAdapter.AgentscopeSession) adapter.start(request);
        return session.agent;
    }

    /**
     * 每处一个独立的工作目录，放在 {@code target/} 下——**刻意不用 {@code @TempDir}**：
     * 框架的 {@code WorkspaceIndex} 会在工作目录里开一个 H2 文件库（{@code .index/workspace.db}）并且不关，
     * 用例结束时 {@code @TempDir} 删不掉它（Windows 上表现为「另一个程序正在使用此文件」），
     * 于是「用例全绿、收尾报错」。放在构建产物目录里就没这个问题（{@code mvn clean} 一并清掉）。
     */
    private static Path workspaceDir(String name) {
        return Path.of("target", "shared-workspace-test", name + "-" + System.nanoTime());
    }

    private AgentscopeRuntimeAdapter adapter(BaseStore store, Path workspaceDir) {
        return new AgentscopeRuntimeAdapter(NO_MODEL, stateStore, workspaceDir, store);
    }

    /** 表由迁移脚本建，不由应用建；用例里就地建两张同口径的表（H2 侧两处 CLOB/TEXT 差异照着框架方言抄）。 */
    private void createTables() throws Exception {
        String ddl = "CREATE TABLE agentscope_store ("
                + "  namespace_path VARCHAR(2048) NOT NULL,"
                + "  item_key       VARCHAR(255)  NOT NULL,"
                + "  value_json     CLOB          NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ")";
        String sessions = "CREATE TABLE agentscope_sessions ("
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
            stmt.execute(sessions);
        }
    }
}
