package com.djzy.assistant.agentstate;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * 沙箱的「跨实例执行锁」（H-11）：同一个用户，同时只能有一轮真的在容器里跑。
 *
 * <p><b>它防的是什么</b>：沙箱的隔离槽是<b>按用户</b>分的（{@code IsolationScope.USER}，见 {@code SandboxSettings}）。
 * 槽位只有一个，而框架把「这个槽位上现在是哪个容器」记在槽位里——同一个用户的两轮如果几乎同时开始
 * （一台实例上的两个会话、或者两台实例各一轮），两边都还看不到对方写下的记录，于是各自新建一个容器，
 * 最后<b>后写的覆盖先写的</b>（这个行为有实测用例，见台账 L-21）。聊天记录不受影响（它在另一把键上），
 * 坏掉的是容器那点状态——但这种坏法不报错，只是悄悄发生，所以值得上一把锁。
 *
 * <p><b>框架这边已经给了什么</b>：框架有一个扩展点 {@link SandboxExecutionGuard}，语义正好是
 * 「进沙箱之前先拿锁、整轮跑完再松开」（{@code SandboxManager} 在 acquire 之前 {@code tryEnter}，
 * 在 release 之后关掉那个 lease）。它自己只提供 {@code noop}（也就是不锁），分布式实现放在扩展包里。
 * 所以这里要做的事只有一件：<b>把现成的实现接上，不自己写锁</b>。
 *
 * <p><b>为什么选 JDBC 版，而不是 Redis 版</b>：两个都能用，但这个模块本来就有 {@link DataSource}，
 * 而 Redis 版要的是 {@code redis.clients.jedis.UnifiedJedis}——为了一个锁把 Jedis 引进来不划算。
 * 接法也不是「按数据库挑实现」，而是<b>把方言交给框架</b>：{@code JdbcSandboxExecutionGuard} 自己不做任何
 * 与数据库相关的事，锁到底是什么语义由方言说了算，于是生产与测试共用一份代码：
 * <ul>
 *   <li><b>生产 PG</b>：{@code pg_try_advisory_lock}，锁挂在那条专用连接上，
 *       <b>进程崩了连接就断、锁自动松开</b>——不会出现「进程没了、锁还占着」的死锁；</li>
 *   <li><b>测试 H2 / 其它库</b>：退化成一张锁表（{@code agentscope_distributed_locks}），靠 {@code INSERT}
 *       撞主键来抢、{@code DELETE} 来放。能跑通，但<b>没有租约</b>：持有者进程被杀，那一行会留到有人手工清。</li>
 * </ul>
 *
 * <p><b>两个代价，写在明面上免得被当成没做</b>：
 * <ol>
 *   <li><b>后到的那一轮要等</b>：等不到就按超时失败（错误里带超时秒数），不会无限挂着。等待上限由
 *       {@code agent-service.sandbox.lock-timeout} 决定（默认 5 分钟）；</li>
 *   <li><b>PG 这条路会占一条连接</b>，占的时长就是「这一轮的沙箱活着的时间」。也就是：同时有 N 个用户在跑脚本，
 *       就有 N 条连接被锁占着，连接池必须给够。这个开销只在 {@code agent-service.sandbox.enabled=true} 时存在。</li>
 * </ol>
 *
 * <p><b>为什么 {@code autoCreateTable(false)}</b>：与本模块另外两个存储（{@link PlatformAgentStateStore}、
 * {@link PlatformWorkspaceStore}）同一条规矩——表的唯一来源是迁移脚本，不在运行期建表。
 * PG 走 advisory lock 根本不用表；H2 那张锁表由框架在第一次用到的时候自己 {@code CREATE TABLE IF NOT EXISTS}
 * （这是框架的行为，不是这里放宽的口子）。
 */
public final class PlatformSandboxExecutionGuard {

    /**
     * 等锁的上限：等不到就让这一轮失败。
     *
     * <p>为什么不设成「一直等」：对话场景里，一个请求挂着不出声比明确报错更难排查；
     * 而沙箱被占住通常是「另一个会话正在跑脚本」，用户重发一次往往就好了。
     */
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(5);

    private PlatformSandboxExecutionGuard() {}

    /**
     * 建一把「按用户的沙箱执行锁」：锁的后端跟着 DataSource 的方言走（生产 PG、测试 H2 共用这份代码）。
     *
     * @param dataSource 与其它存储共用同一个连接池
     * @param lockTimeout 等锁上限；传 {@code null} 用 {@link #DEFAULT_LOCK_TIMEOUT}
     */
    public static SandboxExecutionGuard create(DataSource dataSource, Duration lockTimeout) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
                .autoCreateTable(false)
                .build();
        return JdbcSandboxExecutionGuard.builder(dialect)
                .lockTimeout(lockTimeout == null ? DEFAULT_LOCK_TIMEOUT : lockTimeout)
                .build();
    }
}
