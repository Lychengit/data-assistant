package com.djzy.assistant.agentweb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.auth.InMemoryEntryTicketStore;
import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.CrossInstanceTurnRelay;
import com.djzy.assistant.agentweb.session.InMemorySessionTurnGate;
import com.djzy.assistant.agentweb.session.InMemoryTurnStopSignalStore;
import com.djzy.assistant.agentweb.session.LiveTurnChannel;
import com.djzy.assistant.agentweb.session.bus.InMemoryMessageBus;
import com.djzy.assistant.agentweb.session.SessionCatalog;
import com.djzy.assistant.agentweb.session.TurnStopChannel;
import com.djzy.assistant.agentweb.session.StreamRecord;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentstate.PlatformSessionStore;
import com.djzy.assistant.agentstate.PlatformTurnStore;
import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolInvoker;
import com.djzy.assistant.agentweb.web.ApiException;
import com.djzy.assistant.common.eventlog.AppendOnlyEventLog;
import com.djzy.assistant.common.eventlog.EventLogConfig;
import com.djzy.assistant.common.eventlog.FileEventLogCursorStore;
import com.djzy.assistant.common.eventlog.LogOnlyEventPublisher;
import com.djzy.assistant.common.sse.SseEventType;
import com.djzy.assistant.core.runtime.RuntimeRegistry;
import com.djzy.assistant.core.sse.SseProjector;
import com.djzy.assistant.core.sse.ThinkingVisibility;
import com.djzy.assistant.runtime.noop.NoopRuntimeAdapter;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.RuntimeCapabilities;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import com.djzy.assistant.spi.Snapshot;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 主对话流程（§19.4 / §19.6）：券、回放、归属、并发、限流、日志先行。 */
class ChatServiceTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @TempDir
    Path dir;

    private AppendOnlyEventLog eventLog;
    private EntryTicketService tickets;
    private ChatSessionRegistry registry;
    private SessionCatalog catalog;
    private AgentStateStore stateStore;
    private AgentServiceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentServiceProperties();
        properties.setEventLogDir(dir.resolve("log").toString());
        properties.setWorkspaceDir(dir.resolve("workspace").toString());
        // 会话状态用框架自带的**内存**实现：这个用例关心的是接入层的券 / 回放 / 归属 / 并发语义，
        // 真库语义（CAS、方言、行锁）由 JdbcAgentStateStoreTest 覆盖，两边各管一段。
        stateStore = new InMemoryAgentStateStore();
        registry = new ChatSessionRegistry(256, 64);
        catalog = new SessionCatalog(stateStore, new PlatformSessionStore(stateStore), liveTurns);
        tickets = new EntryTicketService(new InMemoryEntryTicketStore(), Duration.ofSeconds(60));
        EventLogConfig config = new EventLogConfig(dir.resolve("log"), "test", 0, 0, 0, Duration.ofHours(1));
        eventLog = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()));
    }

    @AfterEach
    void tearDown() {
        eventLog.close();
    }

    @Test
    void 一轮对话能从头订阅到_user_到_done() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        List<StreamRecord> records = service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThat(records).isNotNull();
        // 第一条是平台记下的「用户问了什么」（会话号由 POST /sessions 直接返回，不再需要一个 session 事件）
        assertThat(records.get(0).event().type()).isEqualTo(SseEventType.USER);
        assertThat(records).extracting(r -> r.event().type()).contains(SseEventType.TOKEN, SseEventType.DONE);
        assertThat(records).extracting(StreamRecord::seq).isSorted().doesNotHaveDuplicates();
        assertThat(records.get(records.size() - 1).seq()).isEqualTo(records.size() - 1);
    }

    @Test
    void 入场券一次性_重放被拒() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.attach(issued.ticket(), -1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(401));
    }

    @Test
    void 断线续传只补没看过的那一段() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());
        List<StreamRecord> all = service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));
        long seen = all.get(0).seq();

        EntryTicketService.IssuedTicket reconnect = tickets.issue(ALICE, sessionId, issued.turnId());
        List<StreamRecord> rest = service.attach(reconnect.ticket(), seen).collectList().block(Duration.ofSeconds(5));

        assertThat(rest).isNotEmpty();
        assertThat(rest).allSatisfy(record -> assertThat(record.seq()).isGreaterThan(seen));
    }

    @Test
    void 同一会话的第二轮不会被上一轮的_done_掐断() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket first = service.startTurn(ALICE, "t", sessionId, "第一问", List.of());
        List<StreamRecord> firstTurn = service.attach(first.ticket(), -1).collectList().block(Duration.ofSeconds(5));
        long firstTurnLastSeq = firstTurn.get(firstTurn.size() - 1).seq();

        EntryTicketService.IssuedTicket second = service.startTurn(ALICE, "t", sessionId, "第二问", List.of());
        List<StreamRecord> secondTurn = service.attach(second.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThat(secondTurn).isNotEmpty();
        StreamRecord last = secondTurn.get(secondTurn.size() - 1);
        assertThat(last.event().type()).isEqualTo(SseEventType.DONE);
        assertThat(last.event().payload()).containsEntry("turnId", second.turnId());
        assertThat(last.seq()).isGreaterThan(firstTurnLastSeq);
    }

    @Test
    void 别人的会话号换不到别人的回答() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        // 即使伪造出一个「bob 的券」指向 alice 的会话，回放位按归属过滤，什么都拿不到
        String forged = tickets.issue(BOB, sessionId, "turn").ticket();
        assertThatThrownBy(() -> service.attach(forged, -1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void 上一轮没跑完时拒绝并发发起() {
        ChatService service = service(new NeverEndingRuntime(), 10);
        String sessionId = service.createSession(ALICE);
        service.startTurn(ALICE, "t", sessionId, "第一个问题", List.of());

        assertThatThrownBy(() -> service.startTurn(ALICE, "t", sessionId, "第二个问题", List.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void 超出每分钟轮次上限被限流() {
        ChatService service = service(new NoopRuntimeAdapter(), 1);
        String first = service.createSession(ALICE);
        String second = service.createSession(ALICE);
        service.startTurn(ALICE, "t", first, "第一个", List.of());

        assertThatThrownBy(() -> service.startTurn(ALICE, "t", second, "第二个", List.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(429));
    }

    @Test
    void 事件先落日志再下发() throws Exception {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());
        service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));
        eventLog.flush();

        String log = Files.readString(eventLog.config().logFile());
        assertThat(log).contains(sessionId).contains("TURN_START").contains("TURN_END");
    }

    @Test
    void 空消息被拒() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        assertThatThrownBy(() -> service.startTurn(ALICE, "t", sessionId, "  ", List.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 没配模型供应商时报出去哪儿配而不是请稍后再试() {
        ChatService service = service(new MisconfiguredRuntime(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        List<StreamRecord> records = service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        StreamRecord error = records.stream()
                .filter(record -> record.event().type() == SseEventType.ERROR)
                .findFirst()
                .orElseThrow();
        assertThat(error.event().payload()).containsEntry("code", AgentErrorCode.LLM_UNAVAILABLE.name());
        assertThat(String.valueOf(error.event().payload().get("message"))).contains("模型供应商");
    }
    /**
     * 停止（§19.4）：停掉这一轮、取消运行时，并且**只收尾一次**。
     *
     * <p>「只收尾一次」是幂等的核心：轮次结束谁都能触发（停止、正常跑完、流异常），
     * 没有这条约束，同一轮会出现两条结束记录、两条 {@code done}。
     */
    @Test
    void 停止会取消运行时且重复停止不会重复收尾() {
        ControllableRuntime runtime = new ControllableRuntime();
        ChatService service = service(runtime, 100);
        String sessionId = service.createSession(ALICE);
        service.startTurn(ALICE, "t", sessionId, "上个月心内科各医生门诊量排名", List.of());

        Map<String, Object> stopped = service.stop(ALICE, sessionId);
        Map<String, Object> stoppedAgain = service.stop(ALICE, sessionId);

        assertThat(stopped).containsEntry("stopped", true);
        assertThat(stoppedAgain).containsEntry("stopped", true);
        assertThat(runtime.cancelReasons()).containsExactly("user_stop");
        // 已经产出的内容（用户的提问）原样保留：停止不是删除
        assertThat(recordsOf(service, sessionId))
                .anySatisfy(record -> assertThat(record.event().type()).isEqualTo(SseEventType.USER));
        // 只该有一条 done
        assertThat(recordsOf(service, sessionId).stream()
                        .filter(record -> record.event().type() == SseEventType.DONE)
                        .count())
                .isEqualTo(1);
    }

    /**
     * 停止请求落在**别的实例**上时，本实例也必须停下来。
     *
     * <p>这就是「不粘性部署」要求的跨副本语义：实例 A 收到停止请求、真正在跑的轮次在实例 B，
     * A 只能往共享存储里放信号，B 在推进事件时看到它并自行取消。
     */
    @Test
    void 别的实例写入停止信号时本实例也会停() {
        ControllableRuntime runtime = new ControllableRuntime();
        ChatService service = service(runtime, 100);
        String sessionId = service.createSession(ALICE);
        service.startTurn(ALICE, "t", sessionId, "问题", List.of());
        String turnId = registry.getOrCreate(sessionId, ALICE).currentTurnId();

        // 只写共享信号，不碰本实例的任何方法——等价于「请求打到了别的实例上」
        stopSignals.request(sessionId, turnId);
        runtime.emit(AgentEvent.builder(AgentEventType.TEXT_DELTA)
                .session(sessionId)
                .turn(turnId)
                .trace("t")
                .put("delta", "x")
                .build());

        assertThat(runtime.cancelReasons()).containsExactly("user_stop");
        // 停止之后这一轮不该再产出内容
        assertThat(recordsOf(service, sessionId).stream()
                        .filter(record -> record.event().type() == SseEventType.TOKEN)
                        .count())
                .isZero();
    }

    /**
     * 停止的**推送**一到就当场取消（H-09）：一个流事件都不发，取消照样发生。
     *
     * <p>这一项要解决的正是上一条的延迟：原来跨实例停止要等「下一个流事件」才生效，
     * 模型卡在一次很慢的调用上时，用户点完停止要愣很久。现在持有这一轮的那台实例
     * 收到推送就取消——所以本用例**刻意不推任何事件**，这恰好证明「没有等事件」。
     */
    @Test
    void 别的实例推来的停止当场生效不必等到下一个事件() {
        ControllableRuntime runtime = new ControllableRuntime();
        ChatService service = service(runtime, 100);
        String sessionId = service.createSession(ALICE);
        service.startTurn(ALICE, "t", sessionId, "问题", List.of());
        String turnId = registry.getOrCreate(sessionId, ALICE).currentTurnId();

        // 只往共享总线上推一条，等价于「另一台实例点了停止」（本实例构造时已经订阅上了）
        new TurnStopChannel(stopBus).publish(ALICE, sessionId, turnId);

        assertThat(runtime.cancelReasons()).containsExactly("user_stop");
    }

    /**
     * 推送说的不是本实例正在跑的那一轮时，什么都不做。
     *
     * <p>推送是广播，每个实例都收得到，所以「不是我在跑」是常态：可能这一轮跑在别的机器上，
     * 也可能本实例已经翻到下一轮了。这两种情况都不该被别人的停止误伤。
     */
    @Test
    void 推送说的不是本实例这一轮时不受影响() {
        ControllableRuntime runtime = new ControllableRuntime();
        ChatService service = service(runtime, 100);
        String sessionId = service.createSession(ALICE);
        service.startTurn(ALICE, "t", sessionId, "问题", List.of());

        // 同一个会话、但不是正在跑的那一轮（例如用户已经翻到下一轮，或者这是别台机器上的轮次号）
        new TurnStopChannel(stopBus).publish(ALICE, sessionId, "turn-not-mine");

        assertThat(runtime.cancelReasons()).isEmpty();
        assertThat(registry.getOrCreate(sessionId, ALICE).running()).isTrue();
    }

    /** 把回放位读干净：停止之后流会自己收（done 到手就结束）。 */
    private List<StreamRecord> recordsOf(ChatService service, String sessionId) {
        return service.attach(service.resumeTicket(ALICE, sessionId).ticket(), -1)
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    /** 可以被用例直接推事件、并且记录 cancel 的运行时装。 */
    private static final class ControllableRuntime implements AgentRuntimePort {
        private final NoopRuntimeAdapter delegate = new NoopRuntimeAdapter();
        private final reactor.core.publisher.Sinks.Many<AgentEvent> sink =
                reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer();
        private final List<String> cancelReasons = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public String id() {
            return "controllable";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return delegate.capabilities();
        }

        void emit(AgentEvent event) {
            sink.tryEmitNext(event);
        }

        List<String> cancelReasons() {
            return List.copyOf(cancelReasons);
        }

        @Override
        public AgentSession start(AgentRunRequest request) {
            return delegate.start(request);
        }

        @Override
        public org.reactivestreams.Publisher<AgentEvent> stream(AgentSession session, AgentTurn turn) {
            return sink.asFlux();
        }

        @Override
        public void confirm(AgentSession session, ConfirmDecision decision) {
            delegate.confirm(session, decision);
        }

        @Override
        public void cancel(AgentSession session, String reason) {
            cancelReasons.add(reason);
        }

        @Override
        public Snapshot snapshot(AgentSession session) {
            return delegate.snapshot(session);
        }

        @Override
        public AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request) {
            return delegate.resume(sessionId, snapshot, request);
        }

        @Override
        public void close(AgentSession session) {
            delegate.close(session);
        }
    }
    /** 停止信号：单测用内存版；用例可以直接往里写，模拟「停止请求落在别的实例上」。 */
    private final InMemoryTurnStopSignalStore stopSignals = new InMemoryTurnStopSignalStore();

    /**
     * 停止推送用的总线（H-09）：用例往同一条总线上推一条，就等于「别的实例把停止推过来了」。
     *
     * <p>每个 {@code ChatService} 各拿一个自己的 {@link TurnStopChannel}（同一个 {@code ChatService}
     * 只订阅一次），所以这里共用一条总线、不共用推送口——这与真实部署里「每台实例各自订阅」一致。
     */
    private final InMemoryMessageBus stopBus = new InMemoryMessageBus();

    /** 轮次坑位：单测用内存版（多副本语义由 RedisSessionTurnGateTest 对着真 Redis 验）。 */
    private final InMemorySessionTurnGate turnGate =
            new InMemorySessionTurnGate(java.time.Duration.ofMinutes(15));

    /** 「当前这一轮」的开始标记：挂在同一个内存状态库上，跟生产一样同表不同键。 */
    private final PlatformLiveTurnStore liveTurns = new PlatformLiveTurnStore(stateStore);

    private ChatService service(AgentRuntimePort runtime, int turnsPerMinute) {
        return service(runtime, turnsPerMinute, ToolPlane.empty());
    }

    private ChatService service(AgentRuntimePort runtime, int turnsPerMinute, ToolPlane toolPlane) {
        RuntimeRegistry runtimes = new RuntimeRegistry(List.of(runtime), List.of(runtime.id()), runtime.id());
        // 跨实例接流（T1-09）：单测里用很短的轮询与等待窗口；跨实例语义本身由 CrossInstanceTurnRelayTest 覆盖
        CrossInstanceTurnRelay remoteTurns = new CrossInstanceTurnRelay(
                catalog,
                turnGate,
                new LiveTurnChannel(new InMemoryMessageBus(), 200),
                java.time.Duration.ofMillis(20),
                java.time.Duration.ofMillis(20),
                java.time.Duration.ofSeconds(2),
                properties.turnLease());
        return new ChatService(
                registry,
                catalog,
                tickets,
                runtimes,
                toolPlane,
                new PlatformTurnStore(stateStore),
                new SseProjector(() -> ThinkingVisibility.STEP_CARD_ONLY),
                new TurnRateLimiter(turnsPerMinute),
                new LogOnlyEventPublisher(eventLog),
                stopSignals,
                new TurnStopChannel(stopBus),
                turnGate,
                liveTurns,
                remoteTurns,
                new LiveTurnChannel(new InMemoryMessageBus(), 200),
                "test-instance",
                properties);
    }

    /** 永不结束的运行时：用来验证「上一轮还在跑」这一条。 */
    private static final class NeverEndingRuntime implements AgentRuntimePort {
        private final NoopRuntimeAdapter delegate = new NoopRuntimeAdapter();

        @Override
        public String id() {
            return "blocking";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public AgentSession start(AgentRunRequest request) {
            return delegate.start(request);
        }

        @Override
        public org.reactivestreams.Publisher<AgentEvent> stream(AgentSession session, AgentTurn turn) {
            return Flux.<AgentEvent>never().mergeWith(Mono.<AgentEvent>never());
        }

        @Override
        public void confirm(AgentSession session, ConfirmDecision decision) {
            delegate.confirm(session, decision);
        }

        @Override
        public void cancel(AgentSession session, String reason) {
            delegate.cancel(session, reason);
        }

        @Override
        public Snapshot snapshot(AgentSession session) {
            return delegate.snapshot(session);
        }

        @Override
        public AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request) {
            return delegate.resume(sessionId, snapshot, request);
        }

        @Override
        public void close(AgentSession session) {
            delegate.close(session);
        }
    }
    /** 「模型供应商没配」的运行时装不出来：start 直接抛可自解释的 RuntimeMisconfiguredException。 */
    private static final class MisconfiguredRuntime implements AgentRuntimePort {
        private final NoopRuntimeAdapter delegate = new NoopRuntimeAdapter();

        @Override
        public String id() {
            return "misconfigured";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public AgentSession start(AgentRunRequest request) {
            throw new RuntimeMisconfiguredException("尚未配置可用的大模型：请管理员在管理端「模型供应商」页配置并启用一个");
        }

        @Override
        public org.reactivestreams.Publisher<AgentEvent> stream(AgentSession session, AgentTurn turn) {
            return delegate.stream(session, turn);
        }

        @Override
        public void confirm(AgentSession session, ConfirmDecision decision) {
            delegate.confirm(session, decision);
        }

        @Override
        public void cancel(AgentSession session, String reason) {
            delegate.cancel(session, reason);
        }

        @Override
        public Snapshot snapshot(AgentSession session) {
            return delegate.snapshot(session);
        }

        @Override
        public AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request) {
            return delegate.resume(sessionId, snapshot, request);
        }

        @Override
        public void close(AgentSession session) {
            delegate.close(session);
        }
    }

    // ---------- 技能下发（H-06a）：平台把「这一轮可见的技能」交给运行时的这条路 ----------

    @Test
    void 没开技能下发时_不问网关要技能清单() {
        // 开关关着（默认）：每一轮都不该为此多打一次网关——没有技能用它，那就是白花的往返。
        CountingToolPlane tools = new CountingToolPlane();
        ChatService service = service(new NoopRuntimeAdapter(), 10, tools);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThat(tools.skillQueries).isZero();
    }

    @Test
    void 开了技能下发时_这一轮可见的技能清单进到运行时请求里() {
        properties.setWorkspaceSkills("workspace");
        CountingToolPlane tools = new CountingToolPlane();
        RecordingRuntime runtime = new RecordingRuntime();
        ChatService service = service(runtime, 10, tools);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThat(tools.skillQueries).isEqualTo(1);
        assertThat(runtime.lastRequest.visibleSkills())
                .as("清单要到得了运行时：技能内容由运行时写进工作区")
                .containsExactly("doctor_income");
    }

    /** 会数「被问过几次技能清单」的工具面；其余照骨架形态（没有工具）。 */
    private static final class CountingToolPlane implements ToolPlane {
        private int skillQueries;

        @Override
        public ToolCatalog catalogFor(String bearerToken) {
            return ToolCatalog.empty();
        }

        @Override
        public ToolInvoker invokerFor(String bearerToken) {
            return request -> Mono.empty();
        }

        @Override
        public List<String> skillsFor(String bearerToken) {
            skillQueries++;
            return List.of("doctor_income");
        }
    }

    /** 会把这一轮的请求记下来的运行时：用来断言「平台往运行时里塞了什么」。 */
    private static final class RecordingRuntime implements AgentRuntimePort {
        private final NoopRuntimeAdapter delegate = new NoopRuntimeAdapter();
        private AgentRunRequest lastRequest;

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public AgentSession start(AgentRunRequest request) {
            this.lastRequest = request;
            return delegate.start(request);
        }

        @Override
        public org.reactivestreams.Publisher<AgentEvent> stream(AgentSession session, AgentTurn turn) {
            return delegate.stream(session, turn);
        }

        @Override
        public void confirm(AgentSession session, ConfirmDecision decision) {
            delegate.confirm(session, decision);
        }

        @Override
        public void cancel(AgentSession session, String reason) {
            delegate.cancel(session, reason);
        }

        @Override
        public Snapshot snapshot(AgentSession session) {
            return delegate.snapshot(session);
        }

        @Override
        public AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request) {
            return delegate.resume(sessionId, snapshot, request);
        }

        @Override
        public void close(AgentSession session) {
            delegate.close(session);
        }
    }
}
