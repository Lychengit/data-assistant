package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 沙箱隔离这条假设的护栏（台账 L-21）：**一个用户一个沙箱槽位，不同用户不会互相捡到对方的容器**。
 *
 * <p>为什么值得单独钉一条：平台的沙箱装配里，隔离粒度是**显式写死** {@code IsolationScope.USER} 的
 * （DR-44：不敢靠框架的兜底值，兜底值不会回填到 {@code SandboxContext} 上）。但写死不等于生效——
 * 框架真正用来算槽位的是 {@code SandboxIsolationKey.resolve(sandboxContext.getIsolationScope(), 本轮上下文, agentId)}，
 * 也就是说：**只要我们造出来的那个上下文没带上 USER，或者算键时读的不是它，隔离就静默失效**，
 * 表现是「两个用户的脚本跑在同一个容器里」——这种失败不会报错，只会悄悄发生。
 *
 * <p>所以这里不是「读一遍代码觉得没问题」，而是把三件事写成用例：
 * <ol>
 *   <li>平台按生产路径造出来的 {@code SandboxContext}（含 H-06b 那条带技能投影的路径）确实带 USER；</li>
 *   <li>拿它喂给框架的 {@code SandboxManager}（真类，只把「起容器」换成假客户端）：不同用户 → 各自新建，
 *       同一个用户（哪怕换了会话）→ 回到自己那一个；</li>
 *   <li>顺带把「没有执行锁时会怎样」也记下来：同一个用户并发两轮会各起一个沙箱、状态后写的覆盖先写的——
 *       这正是 H-11 要防的事，现在那把锁已经接上了（{@code agent-service.sandbox.distributed-lock}，默认开）。</li>
 *   <li>配了执行锁时，框架确实会「进沙箱之前拿、放开沙箱之后松」——这一条是给 H-11 的护栏：
 *       光把锁挂到装配对象上不算接上了，得证明框架真的会用它（见本类最后一条用例）。</li>
 * </ol>
 */
class SandboxIsolationTest {

    private static final String AGENT_NAME = "doctor-data-assistant";

    private static final Path STAGING = Path.of("target", "sandbox-isolation-staging");

    private static final SandboxSettings SANDBOX = new SandboxSettings(
            true,
            SandboxSettings.DEFAULT_IMAGE,
            SandboxSettings.DEFAULT_WORKSPACE_ROOT,
            null,
            null,
            null,
            SandboxSettings.DEFAULT_SHARED_PREFIXES,
            null);

    private static RuntimeContext call(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static SandboxIsolationKey keyOf(SandboxContext context, RuntimeContext call) {
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(context.getIsolationScope(), call, AGENT_NAME);
        assertTrue(key.isPresent(), "隔离键必须算得出来（算不出来就等于「不做状态隔离」）");
        return key.get();
    }

    @Test
    void 平台造出来的沙箱上下文带的是用户级隔离() {
        for (SandboxContext context : List.of(
                SANDBOX.toFilesystemSpec().toSandboxContext(STAGING),
                SANDBOX.toProjectingFilesystemSpec().toSandboxContext(STAGING))) {
            assertEquals(
                    IsolationScope.USER,
                    context.getIsolationScope(),
                    "沙箱上下文必须显式带 USER：框架算隔离槽位读的就是它，缺了就没有多租户隔离");
        }
    }

    @Test
    void 不同用户落在不同槽位_同一个用户换会话仍是同一个槽位() {
        SandboxContext context = SANDBOX.toProjectingFilesystemSpec().toSandboxContext(STAGING);

        SandboxIsolationKey aliceOne = keyOf(context, call("alice", "s-1"));
        SandboxIsolationKey aliceTwo = keyOf(context, call("alice", "s-2"));
        SandboxIsolationKey bob = keyOf(context, call("bob", "s-1"));

        assertEquals(aliceOne, aliceTwo, "同一个用户的不同会话必须回到同一个槽位——这正是「一个用户一个容器」");
        assertNotEquals(aliceOne, bob, "不同用户必须落在不同槽位，否则两个人的脚本会跑进同一个容器");
    }

    @Test
    void 沙箱状态按用户分开放_甲存的状态乙读不到() throws Exception {
        SandboxContext context = SANDBOX.toFilesystemSpec().toSandboxContext(STAGING);
        SessionSandboxStateStore store = new SessionSandboxStateStore(new InMemoryAgentStateStore(), AGENT_NAME);

        SandboxIsolationKey alice = keyOf(context, call("alice", "s-1"));
        SandboxIsolationKey bob = keyOf(context, call("bob", "s-1"));

        store.save(alice, "{\"sessionId\":\"alice-container\"}");
        assertEquals(
                "{\"sessionId\":\"alice-container\"}",
                store.load(alice).orElseThrow(),
                "甲自己的状态要读得到（同一个用户换实例时靠它把沙箱接回来）");
        assertTrue(store.load(bob).isEmpty(), "乙不能读到甲的沙箱状态：读到了就等于两个用户共用一个容器");

        store.delete(alice);
        assertTrue(store.load(alice).isEmpty(), "清状态要清得掉（平台每轮开始前就靠这一步让框架重新投影技能，见 L-20）");
    }

    @Test
    void 不同用户不会捡到对方的沙箱_同一个用户会回到自己那一个() throws Exception {
        SandboxContext context = SANDBOX.toFilesystemSpec().toSandboxContext(STAGING);
        RecordingSandboxClient client = new RecordingSandboxClient();
        SessionSandboxStateStore stateStore = new SessionSandboxStateStore(new InMemoryAgentStateStore(), AGENT_NAME);
        // 执行锁刻意给 noop：这条用例只看「槽位算得对不对」，锁的事归 H-11（下面那条用例会记下没有锁时的行为）
        SandboxManager manager = new SandboxManager(client, stateStore, AGENT_NAME, SandboxExecutionGuard.noop());

        RuntimeContext aliceCall = call("alice", "s-1");
        RuntimeContext bobCall = call("bob", "s-1");

        SandboxAcquireResult aliceFirst = manager.acquire(context, aliceCall);
        manager.persistState(aliceFirst, context, aliceCall); // 一轮结束：状态落进 alice 的槽位
        manager.release(aliceFirst);

        manager.acquire(context, bobCall);

        SandboxAcquireResult aliceSecond = manager.acquire(context, aliceCall);

        assertEquals(
                List.of("create:c-1", "create:c-2", "resume:c-1"),
                client.operations,
                "顺序必须是：alice 新建 → bob 也新建（不能 resume alice 那台）→ alice 再进来时 resume 回自己那台");
        assertEquals(
                "c-1",
                aliceSecond.getSandbox().getState().getSessionId(),
                "alice 第二轮必须接回她自己上一次那台沙箱");
    }

    @Test
    void 没有执行锁时_同一个用户并发两轮会各起一个沙箱() throws Exception {
        SandboxContext context = SANDBOX.toFilesystemSpec().toSandboxContext(STAGING);
        RecordingSandboxClient client = new RecordingSandboxClient();
        SessionSandboxStateStore stateStore = new SessionSandboxStateStore(new InMemoryAgentStateStore(), AGENT_NAME);
        SandboxManager manager = new SandboxManager(client, stateStore, AGENT_NAME, SandboxExecutionGuard.noop());

        RuntimeContext aliceCall = call("alice", "s-1");

        // 模拟「两台实例同时开始同一个人的一轮」：两次 acquire 之间没有任何 persist，
        // 所以第二个拿不到第一个的状态（状态要等这一轮结束才写），于是各自新建一个。
        SandboxAcquireResult first = manager.acquire(context, aliceCall);
        SandboxAcquireResult second = manager.acquire(context, aliceCall);

        assertEquals(
                List.of("create:c-1", "create:c-2"),
                client.operations,
                "没有执行锁时，同一个用户的并发两轮会各起一个沙箱——这是 H-11 要防的那个场景（当前默认档不开沙箱，还没有真实暴露面）");

        // 两条状态写的是同一个槽位：谁后写谁赢，先写的那一轮的容器里的东西就丢了。
        manager.persistState(first, context, aliceCall);
        manager.persistState(second, context, aliceCall);
        assertEquals(
                "c-2",
                stateStore
                        .load(keyOf(context, aliceCall))
                        .orElseThrow()
                        .replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1"),
                "同一个槽位最后一次写入生效：这就是「并发两轮会互相覆盖」的具体含义");
    }

    @Test
    void 配了执行锁时_框架会在拿沙箱之前先拿锁_放开沙箱之后才松手() throws Exception {
        SandboxContext context = SANDBOX.toFilesystemSpec().toSandboxContext(STAGING);
        // 执行锁与沙箱客户端**共用一条时间线**：这样一条断言就能同时看到「锁在容器之前拿、在放开之后松」。
        List<String> timeline = new ArrayList<>();
        RecordingSandboxClient client = new RecordingSandboxClient(timeline);
        SessionSandboxStateStore stateStore = new SessionSandboxStateStore(new InMemoryAgentStateStore(), AGENT_NAME);
        RecordingGuard guard = new RecordingGuard(timeline);
        SandboxManager manager = new SandboxManager(client, stateStore, AGENT_NAME, guard);

        // 用真中间件驱动，而不是自己调 acquire / release：**松锁这件事是中间件做的**
        // （框架的顺序是「先 release（stop + 快照），再 lease.close()」），自己手写一遍
        // 就等于在测自己，测不出「框架到底有没有把锁还回来」。
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem());

        RuntimeContext aliceCall = call("alice", "s-1");
        aliceCall.put(SandboxContext.class, context);
        middleware.acquireForCall(aliceCall);
        middleware.releaseForCall(aliceCall);

        assertEquals(
                List.of("enter:USER:alice", "create:c-1", "exit:USER:alice"),
                timeline,
                "顺序必须是「先拿锁 → 再建容器 → 整轮跑完才松手」。锁名里带用户，否则就退化成一把全局锁（一个人跑脚本、全院排队）；松手在 release 之后，所以「同一个用户两轮并发」的那个窗口正好被挡住");
    }

    /** 只记账的执行锁：用来观察「框架在什么时候拿锁、什么时候松手」。 */
    private static final class RecordingGuard implements SandboxExecutionGuard {

        final List<String> operations;

        RecordingGuard(List<String> timeline) {
            this.operations = timeline;
        }

        @Override
        public SandboxLease tryEnter(SandboxIsolationKey key) {
            operations.add("enter:" + key.getScope().name() + ":" + key.getValue());
            return () -> operations.add("exit:" + key.getScope().name() + ":" + key.getValue());
        }
    }

    /** 只记账、不起真容器的沙箱客户端：用来观察「框架到底要新建还是 resume、要了几个」。 */
    private static final class RecordingSandboxClient implements SandboxClient<SandboxClientOptions> {

        /** 操作时间线；默认自己开一份，也可以与执行锁共用一份（用来看「谁先谁后」）。 */
        final List<String> operations;

        private int counter;

        RecordingSandboxClient() {
            this(new ArrayList<>());
        }

        RecordingSandboxClient(List<String> timeline) {
            this.operations = timeline;
        }

        @Override
        public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, SandboxClientOptions options) {
            String id = "c-" + (++counter);
            operations.add("create:" + id);
            return new StubSandbox(stateOf(id));
        }

        @Override
        public Sandbox resume(SandboxState state) {
            operations.add("resume:" + state.getSessionId());
            return new StubSandbox(state);
        }

        @Override
        public void delete(Sandbox sandbox) {
            operations.add("delete:" + sandbox.getState().getSessionId());
        }

        @Override
        public String serializeState(SandboxState state) {
            return "{\"sessionId\":\"" + state.getSessionId() + "\"}";
        }

        @Override
        public SandboxState deserializeState(String json) {
            return stateOf(json.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1"));
        }

        private static DockerSandboxState stateOf(String sessionId) {
            DockerSandboxState state = new DockerSandboxState();
            state.setSessionId(sessionId);
            return state;
        }
    }

    /** 只实现生命周期那几个方法的沙箱壳子；执行/打包这些不属于这条用例要验的东西。 */
    private static final class StubSandbox implements Sandbox {

        private final SandboxState state;
        private boolean running;

        StubSandbox(SandboxState state) {
            this.state = state;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void close() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public SandboxState getState() {
            return state;
        }

        @Override
        public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            throw new UnsupportedOperationException("这条用例不执行命令");
        }

        @Override
        public InputStream persistWorkspace() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {
            // 无需实现
        }
    }
}
