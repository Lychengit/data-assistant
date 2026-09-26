package com.djzy.assistant.agentstate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 沙箱的跨实例执行锁（H-11）：这里要证的是「同一个用户，同时只放一轮进容器」。
 *
 * <p>做法与 {@link PlatformWorkspaceStoreTest} 一样：**用同一个库建两把锁对象**，
 * 它们之间没有任何内存里的共享，唯一的联系就是那个库——一把抢到了，另一把就得等。
 * 能互相挡住，就说明换一台机器（另起一个进程、连同一个库）也一样挡得住。
 *
 * <p>为什么这条护栏值得单独写：这个锁要防的失败是<b>静默</b>的——同一个用户的两轮各起一个容器，
 * 后写的槽位覆盖先写的，界面上什么都不报（L-21 记的正是这个行为）。
 * 把「抢不到」和「松手后马上能拿到」都钉住，锁才算真的接上了，而不是「配了个参数」。
 *
 * <p>用 H2 而不是 PG：这里验的是锁的语义（互斥 / 不误伤 / 会等），不是数据库本身；
 * 方言由框架按 DataSource 自动识别，所以生产 PG 走的是 advisory lock 那条（进程挂了会自动松），
 * 与这里的表锁实现<b>共用同一份平台代码</b>。
 */
class PlatformSandboxExecutionGuardTest {

    private static final String AGENT_NAME = "doctor-data-assistant";

    private JdbcDataSource dataSource;
    /** 甲实例的锁。 */
    private SandboxExecutionGuard instanceA;
    /** 乙实例的锁：跟甲只有「同一个库」这一层关系。 */
    private SandboxExecutionGuard instanceB;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = newDataSource("sandbox-lock-" + System.nanoTime());
        instanceA = PlatformSandboxExecutionGuard.create(dataSource, Duration.ofSeconds(5));
        instanceB = PlatformSandboxExecutionGuard.create(dataSource, Duration.ofSeconds(5));
        // 先串行地抢一次：非 PG 方言走的是「一张锁表」，框架在第一次用到时才建它，
        // 先热一次身，后面的并发用例就不会同时去建表（那是框架的 DDL，不是这条用例要验的东西）。
        try (SandboxLease warmUp = instanceA.tryEnter(key("warm-up", "warm-up"))) {
            assertNotNull(warmUp);
        }
    }

    @Test
    void 同一个用户_甲拿着锁时乙抢不到_甲松手后乙马上拿到() throws Exception {
        SandboxIsolationKey alice = key("alice", "s-1");

        SandboxLease heldByA = instanceA.tryEnter(alice);

        // 乙换一把「只等 300 毫秒」的锁：它必须抢不到，而且明确报超时——不能默默放行。
        SandboxExecutionGuard impatientB =
                PlatformSandboxExecutionGuard.create(dataSource, Duration.ofMillis(300));
        InterruptedException timeout =
                assertThrows(InterruptedException.class, () -> impatientB.tryEnter(alice));
        assertTrue(
                String.valueOf(timeout.getMessage()).contains("alice"),
                "超时错误里要说清是哪把锁，否则线上只知道「失败了」、不知道是谁占着：" + timeout.getMessage());

        heldByA.close();

        // 甲一松手，乙用原来那把（5 秒上限）应该立刻拿到，而不是等满 5 秒。
        try (SandboxLease afterRelease = instanceB.tryEnter(alice)) {
            assertNotNull(afterRelease, "甲已经松手了，乙必须拿到");
        }
    }

    @Test
    void 不同用户互不阻塞() throws Exception {
        // 锁是「按用户」的：如果它退化成一把全局锁，这条会等到 5 秒超时才失败。
        try (SandboxLease heldForAlice = instanceA.tryEnter(key("alice", "s-1"));
                SandboxLease heldForBob = instanceB.tryEnter(key("bob", "s-1"))) {
            assertNotNull(heldForAlice);
            assertNotNull(heldForBob, "不同用户不该互相挡——挡了就是「一个人跑脚本、全院排队」");
        }
    }

    @Test
    void 抢不到的人会等一会儿_别人松手他就拿到() throws Exception {
        SandboxIsolationKey alice = key("alice", "s-1");
        SandboxLease heldByA = instanceA.tryEnter(alice);

        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try (SandboxLease lease = instanceB.tryEnter(alice)) {
                acquired.countDown();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        waiter.start();

        assertFalse(
                acquired.await(300, TimeUnit.MILLISECONDS),
                "甲还拿着锁，乙不该拿到——这条同时说明「抢不到」是「等」而不是「立刻放行」");

        heldByA.close();

        assertTrue(
                acquired.await(5, TimeUnit.SECONDS),
                "甲松手之后乙必须很快拿到；实际异常=" + failure.get());
        waiter.join(5000);
    }

    /** 隔离键就是「谁在用这个沙箱槽位」：作用域写死 USER（与平台的装配一致）。 */
    private static SandboxIsolationKey key(String userId, String sessionId) {
        return SandboxIsolationKey
                .resolve(
                        IsolationScope.USER,
                        RuntimeContext.builder().userId(userId).sessionId(sessionId).build(),
                        AGENT_NAME)
                .orElseThrow(() -> new IllegalStateException("隔离键算不出来"));
    }

    private static JdbcDataSource newDataSource(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        // 与 PlatformWorkspaceStoreTest 同口径：每个用例一个独立的内存库，互不干扰
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
