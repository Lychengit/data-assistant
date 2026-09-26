package com.djzy.assistant.agentstate;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 平台用的会话状态存储：**框架的 JDBC 实现 + 补上它漏掉的「按 key 删除」**（T1-13）。
 *
 * <p>其余一切都交给框架（{@link JdbcAgentStateStore} + 方言）：版本 CAS、列表状态、按会话删除、
 * 按用户列会话，都是框架已有的能力，我们不重复实现。这里只补一个洞：
 *
 * <p><b>那个洞是什么</b>：{@code AgentStateStore.delete(userId, sessionId, key)} 在接口上是个
 * **空方法**（默认什么都不做，注释写着「支持就覆写」），而框架的 JDBC 实现只覆写了「删整个会话」，
 * 没有覆写「删一个 key」。于是平台侧那些「清掉这一段状态」的调用会**静默失效**——
 * 表现是：一轮跑完，挂起快照 / 轮次开始标记还留在库里；下次读历史时就会把「早就结束的一轮」
 * 当成「还在跑」或「挂起等确认」。静默失效比报错难查得多，所以必须补上。
 *
 * <p><b>怎么补</b>：方言里本来就有现成的语句 {@code sessionStateDeleteByKey}（框架只是没接上），
 * 所以这里不手写 SQL，直接用方言那条。
 *
 * <p><b>唯一的耦合点</b>：框架表用**槽位号**寻址，槽位号 = {@code <userId>:<sessionId>}，
 * 没有 userId 时用占位名 {@link #ANON_USER}（与框架 {@code JdbcAgentStateStore.ANON_USER} 一致）。
 * 这个拼法是框架内部的约定、没有对外暴露，所以我们在这儿照抄一份——
 * {@code PlatformAgentStateStoreTest} 专门有一条用例钉住它：拼错了那条用例会红，
 * 而不是等到线上「删除静默失效」才发现。
 */
public final class PlatformAgentStateStore implements AgentStateStore {

    /** 框架给「没有 userId 的会话」用的占位名；两边必须一致，见类注释里的耦合点说明。 */
    private static final String ANON_USER = "__anon__";

    private final AgentStateStore delegate;
    private final AbstractJdbcDialect dialect;
    private final DataSource dataSource;

    PlatformAgentStateStore(AgentStateStore delegate, AbstractJdbcDialect dialect, DataSource dataSource) {
        this.delegate = delegate;
        this.dialect = dialect;
        this.dataSource = dataSource;
    }

    /**
     * 按 DataSource 建一个：方言自动识别（生产 PG、测试 H2 走同一份代码）。
     *
     * <p>{@code autoCreateTable(false)}：**表的唯一来源是迁移脚本**。不关掉的话，框架在装配方言时
     * 会顺手把 store / snapshot 两张表也建出来（H2 在 PostgreSQL 兼容模式下不认 snapshot 用的 BLOB，
     * 测试会直接起不来），而且多实例同时启动会撞运行期建表的竞态。
     * 反过来说，会话状态表必须先存在——框架的构造器会校验，缺表直接启动失败，这是我们要的失败方式。
     */
    public static PlatformAgentStateStore create(DataSource dataSource) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
                .autoCreateTable(false)
                .build();
        return new PlatformAgentStateStore(new JdbcAgentStateStore(dataSource, dialect), dialect, dataSource);
    }

    /**
     * 删掉这个会话下的某一段状态（框架漏掉的那个能力）。
     *
     * <p>为什么按 key 删而不是删整个会话：同一个会话里并存着多段状态——框架的会话正文
     * （键 {@code agent_state}）与平台侧三个键（{@code platform_turn} / {@code platform_session} /
     * {@code platform_turn_live}）。清「挂起快照」时把会话正文一起删掉，等于把聊天记录抹了。
     */
    @Override
    public void delete(String userId, String sessionId, String key) {
        BoundSql sql = dialect.sessionStateDeleteByKey(slotId(userId, sessionId), key);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql.sql())) {
            bind(stmt, sql.params());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("按 key 删除会话状态失败：key=" + key, e);
        }
    }

    private static String slotId(String userId, String sessionId) {
        return (userId == null || userId.isBlank() ? ANON_USER : userId) + ':' + sessionId;
    }

    private static void bind(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
    }

    // ==================== 以下一律委托给框架实现 ====================

    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(userId, sessionId, key, type);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        return delegate.saveIfVersion(userId, sessionId, key, value, expectedVersion);
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        delegate.save(userId, sessionId, key, value);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(userId, sessionId, key, values);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, sessionId, key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
