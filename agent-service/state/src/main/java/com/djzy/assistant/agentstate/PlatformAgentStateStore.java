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
 *
 * <p><b>第二个洞：沙箱状态根本存不下来</b>（2026-09-27 真机定位）。框架给沙箱状态用的槽位号是
 * <b>路径式</b>的（{@code IsolationScope.USER} → {@code sandbox/user/<agentId>/<userId>}，见框架
 * {@code SessionSandboxStateStore#slotSessionId}），而框架的 JDBC 实现
 * （{@code JdbcAgentStateStore#validateSlotId}）明确拒绝含 {@code /} 或 {@code \} 的槽位号 ——
 * 于是沙箱状态的 save / load / delete <b>每一次都抛 IllegalArgumentException</b>。表现是：容器那点状态
 * （「这台容器还在、工作区已经就绪」）永远写不进去，下一轮只能起一台全新的空容器 —— 上一轮在容器里
 * 生成的文件随之消失，技能里「先生成文件、确认后再上传」这种跨轮流程会直接断掉。修法是在交给框架之前
 * 把 {@code %} / {@code /} / {@code \} 转义掉，读回来时再还原；不含这几个字符的会话号（平台的会话 UUID
 * 就是）逐字不变，所以对既有状态是零影响。
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
        return escape(userId == null || userId.isBlank() ? ANON_USER : userId) + ':' + escape(sessionId);
    }

    /**
     * 把框架不接受的两个字符转义掉（见类注释里的「第二个洞」）。
     *
     * <p>{@code %} 必须一起转义，否则「原本就长 {@code %2F} 的会话号」会被解回成 {@code /}，
     * 两个不同的槽位撞到一起。
     */
    private static String escape(String id) {
        if (id == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(id.length() + 8);
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            switch (c) {
                case '%' -> out.append("%25");
                case '/' -> out.append("%2F");
                case '\\' -> out.append("%5C");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * {@link #escape} 的逆运算。
     *
     * <p>刻意写成一次扫描，而不是「连着 replace 三次」：后者在 {@code %252F} 这种输入上会还原错
     * （先看到的 {@code %2F} 不是转义出来的那一个）。
     */
    private static String unescape(String id) {
        if (id == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '%' && i + 2 < id.length()) {
                String decoded = switch (id.substring(i + 1, i + 3)) {
                    case "25" -> "%";
                    case "2F" -> "/";
                    case "5C" -> "\\";
                    default -> null;
                };
                if (decoded != null) {
                    out.append(decoded);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void bind(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
    }

    // ==================== 以下一律委托给框架实现 ====================
    // 注意：槽位号是「每个方法自己拼的」（框架的 JdbcAgentStateStore 内部拼 <userId>:<sessionId>），
    // 所以每个入口都得先把两个身份转义一遍，漏一个就会在那个入口上重新炸出「会话号不能含路径分隔符」。

    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(escape(userId), escape(sessionId), key, type);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        return delegate.saveIfVersion(escape(userId), escape(sessionId), key, value, expectedVersion);
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        delegate.save(escape(userId), escape(sessionId), key, value);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(escape(userId), escape(sessionId), key, values);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(escape(userId), escape(sessionId), key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(escape(userId), escape(sessionId), key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(escape(userId), escape(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(escape(userId), escape(sessionId));
    }

    /** 回给调用方的必须是**没转义过的**会话号：转义是我们和框架之间的私事。 */
    @Override
    public Set<String> listSessionIds(String userId) {
        Set<String> raw = delegate.listSessionIds(escape(userId));
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        Set<String> out = new java.util.LinkedHashSet<>(raw.size());
        for (String id : raw) {
            out.add(unescape(id));
        }
        return out;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
