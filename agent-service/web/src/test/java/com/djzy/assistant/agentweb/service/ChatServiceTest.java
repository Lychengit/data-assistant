package com.djzy.assistant.agentweb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.auth.InMemoryEntryTicketStore;
import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.FileSessionJournal;
import com.djzy.assistant.agentweb.session.JournalRecord;
import com.djzy.assistant.agentweb.state.FileRuntimeStatePort;
import com.djzy.assistant.agentweb.tool.ToolPlane;
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
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.RuntimeCapabilities;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import com.djzy.assistant.spi.Snapshot;
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

    private FileSessionJournal journal;
    private AppendOnlyEventLog eventLog;
    private EntryTicketService tickets;
    private ChatSessionRegistry registry;
    private AgentServiceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentServiceProperties();
        properties.setEventLogDir(dir.resolve("log").toString());
        properties.setStateDir(dir.resolve("state").toString());
        journal = new FileSessionJournal(dir.resolve("log"), "test", 256);
        registry = new ChatSessionRegistry(journal, 256);
        tickets = new EntryTicketService(new InMemoryEntryTicketStore(), Duration.ofSeconds(60));
        EventLogConfig config = new EventLogConfig(dir.resolve("log"), "test", 0, 0, 0, Duration.ofHours(1));
        eventLog = new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()));
    }

    @AfterEach
    void tearDown() {
        journal.close();
        eventLog.close();
    }

    @Test
    void 一轮对话能从头订阅到_session_到_done() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket issued = service.startTurn(ALICE, "t", sessionId, "你好", List.of());

        List<JournalRecord> records = service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThat(records).isNotNull();
        assertThat(records.get(0).event().type()).isEqualTo(SseEventType.SESSION);
        assertThat(records).extracting(r -> r.event().type()).contains(SseEventType.TOKEN, SseEventType.DONE);
        assertThat(records).extracting(JournalRecord::seq).isSorted().doesNotHaveDuplicates();
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
        List<JournalRecord> all = service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));
        long seen = all.get(0).seq();

        EntryTicketService.IssuedTicket reconnect = tickets.issue(ALICE, sessionId, issued.turnId());
        List<JournalRecord> rest = service.attach(reconnect.ticket(), seen).collectList().block(Duration.ofSeconds(5));

        assertThat(rest).isNotEmpty();
        assertThat(rest).allSatisfy(record -> assertThat(record.seq()).isGreaterThan(seen));
    }

    @Test
    void 同一会话的第二轮不会被上一轮的_done_掐断() {
        ChatService service = service(new NoopRuntimeAdapter(), 10);
        String sessionId = service.createSession(ALICE);
        EntryTicketService.IssuedTicket first = service.startTurn(ALICE, "t", sessionId, "第一问", List.of());
        List<JournalRecord> firstTurn = service.attach(first.ticket(), -1).collectList().block(Duration.ofSeconds(5));
        long firstTurnLastSeq = firstTurn.get(firstTurn.size() - 1).seq();

        EntryTicketService.IssuedTicket second = service.startTurn(ALICE, "t", sessionId, "第二问", List.of());
        List<JournalRecord> secondTurn = service.attach(second.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        assertThat(secondTurn).isNotEmpty();
        JournalRecord last = secondTurn.get(secondTurn.size() - 1);
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

        List<JournalRecord> records = service.attach(issued.ticket(), -1).collectList().block(Duration.ofSeconds(5));

        JournalRecord error = records.stream()
                .filter(record -> record.event().type() == SseEventType.ERROR)
                .findFirst()
                .orElseThrow();
        assertThat(error.event().payload()).containsEntry("code", AgentErrorCode.LLM_UNAVAILABLE.name());
        assertThat(String.valueOf(error.event().payload().get("message"))).contains("模型供应商");
    }
    private ChatService service(AgentRuntimePort runtime, int turnsPerMinute) {
        RuntimeRegistry runtimes = new RuntimeRegistry(List.of(runtime), List.of(runtime.id()), runtime.id());
        return new ChatService(
                registry,
                tickets,
                runtimes,
                ToolPlane.empty(),
                new FileRuntimeStatePort(dir.resolve("state")),
                new SseProjector(() -> ThinkingVisibility.STEP_CARD_ONLY),
                new TurnRateLimiter(turnsPerMinute),
                new LogOnlyEventPublisher(eventLog),
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
}
