package com.djzy.assistant.agentweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentstate.PlatformTurnStore;
import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import com.djzy.assistant.agentweb.service.ChatService;
import com.djzy.assistant.agentweb.service.TurnLimiter;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.CrossInstanceTurnRelay;
import com.djzy.assistant.agentweb.session.SessionCatalog;
import com.djzy.assistant.agentweb.session.StreamRecord;
import com.djzy.assistant.agentweb.session.TurnGateKeys;
import com.djzy.assistant.agentweb.session.TurnStopSignalStore;
import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.common.bus.EventPublisher;
import com.djzy.assistant.core.runtime.RuntimeRegistry;
import com.djzy.assistant.core.sse.SseProjector;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 两个实例同时在线（T1-10）：A / B / C 三条要求都要有据可查。
 *
 * <p>**这里怎么造出「第二个实例」**：实例之间真正共享的只有中间件（状态库、轮次闸门、入场券），
 * 实例各自的只有内存里的会话句柄登记簿。所以实例 B 就是「同一套共享装配 + 另一个
 * {@link ChatSessionRegistry} + 另一个实例名」——它在共享存储看来与「另一台机器」无异。
 * 实例 A 走真实 HTTP 入口（MockMvc），实例 B 直接调 {@link ChatService}。
 *
 * <p>三条要求分别对应：
 * <ul>
 *   <li><b>B 换台机器接着聊</b>：B 能看到 A 建好的会话与历史；A 上没跑过那一轮时换券也必须成功
 *       （券要绑到状态库里的轮次号——从前这里会因为本机没有轮次号而直接失败）；</li>
 *   <li><b>A 断线重连（跨实例）</b>：那一轮在 B 上跑着，A 接流要等到它跑完再整段补过来；
 *       在此之前历史里要把这一轮显示成「进行中」，而不是「中断」；</li>
 *   <li><b>C 实例挂了会话还在</b>：B 上那一轮没了（标记还在、坑位空着），A 上照样能读到完整历史，
 *       并且那一轮有明确的中断说明。</li>
 * </ul>
 *
 * <p>为什么不真起两个进程：这里要验的是**平台自己的协调逻辑**（共享存储 + 本实例内存的边界）。
 * 真起两个 JVM 的冒烟脚本在 {@code deploy/local-windows/two-instance-smoke.ps1}。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:agentwebmulti;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "agent-service.secrets.login-token=" + AgentWebTestSupport.JWT_SECRET,
    "agent-service.runtime-id=noop",
    "agent-service.turns-per-minute=100",
    "agent-service.ticket-store=memory",
    "agent-service.rate-limit-store=memory",
    "agent-service.stop-signal-store=memory",
    // 轮次闸门显式钉回内存：单测不依赖本机 Redis；它在两个实例之间共享，「共享存储」的角色由它扮演
    "agent-service.turn-gate-store=memory",
    // 共享总线也退回内存实现（生产默认 redis）：单测不依赖本机 Redis。
    "agent-service.live-bus=memory",
    "agent-service.event-persist-interval-ms=3600000"
})
@Import(AgentWebH2Config.class)
@AutoConfigureMockMvc
class AgentWebMultiInstanceTest {

    private static final Path WORK_DIR;

    static {
        try {
            WORK_DIR = Files.createTempDirectory("agent-web-multi");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-service.event-log-dir", () -> WORK_DIR.resolve("log").toString());
        registry.add("agent-service.workspace-dir", () -> WORK_DIR.resolve("workspace").toString());
        registry.add("agent-service.instance-id", () -> "instance-a");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatService chatServiceA;

    @Autowired
    private AgentStateStore agentStateStore;

    @Autowired
    private PlatformLiveTurnStore liveTurns;

    @Autowired
    private SessionTurnGate turnGate;

    @Autowired
    private SessionCatalog sessionCatalog;

    @Autowired
    private EntryTicketService tickets;

    @Autowired
    private RuntimeRegistry runtimes;

    @Autowired
    private ToolPlane toolPlane;

    @Autowired
    private PlatformTurnStore turnStore;

    @Autowired
    private SseProjector projector;

    @Autowired
    private TurnLimiter rateLimiter;

    @Autowired
    @Qualifier("eventPublisher")
    private EventPublisher events;

    @Autowired
    private TurnStopSignalStore stopSignals;

    /** 停止推送口（H-09）：和实例 A 用同一个 bean——「推送」这件事本来就是每台实例各自订阅同一频道。 */
    @Autowired
    private com.djzy.assistant.agentweb.session.TurnStopChannel stopChannel;

    @Autowired
    private CrossInstanceTurnRelay relay;

    @Autowired
    private com.djzy.assistant.agentweb.session.LiveTurnChannel liveTurnChannel;

    @Autowired
    private AgentServiceProperties properties;

    /**
     * 假装「这一轮正被别的实例占着」：占上就不放。
     *
     * <p>用例结束时进程内的闸门实例一起消失，坑位不需要手动清理；抢不到说明用例前提就不成立，
     * 直接判失败，免得后面那些「应该显示成进行中」的断言在一个空坑位上得出错误结论。
     */
    private void occupyByRemoteInstance(String userId, String sessionId) {
        try {
            turnGate.acquire(TurnGateKeys.of(userId, sessionId));
        } catch (Exception e) {
            throw new IllegalStateException("用例前提不成立：这个会话的坑位本该是空的", e);
        }
    }
    /** 实例 B：共享存储 / 闸门 / 券都相同，只有「本实例的内存」是新的（这正是换台机器时的差别）。 */
    private ChatService instanceB() {
        return new ChatService(
                new ChatSessionRegistry(properties.getReplayBufferSize(), properties.getSessionCacheSize()),
                sessionCatalog,
                tickets,
                runtimes,
                toolPlane,
                turnStore,
                projector,
                rateLimiter,
                events,
                stopSignals,
                stopChannel,
                turnGate,
                liveTurns,
                relay,
                liveTurnChannel,
                "instance-b",
                properties);
    }

    @Test
    void B_换台实例也能看到同一个会话与历史() throws Exception {
        String sessionId = createSessionOnA();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));

        // 实例 A 走 HTTP 拿到的是这一轮；实例 B 直接调服务，拿到的是同一份（状态库是共享的）
        List<Map<String, Object>> onA = historyViaHttp(sessionId);
        List<com.djzy.assistant.agentweb.session.SessionTranscript.TurnSlice> onB =
                instanceB().history(AgentWebTestSupport.ALICE, sessionId);

        assertThat(onA).hasSize(1);
        assertThat(onB).hasSize(1);
        assertThat(onB.get(0).events())
                .extracting(com.djzy.assistant.agentweb.session.SessionTranscript.StreamEventView::name)
                .containsExactly("user", "token", "done");
    }

    @Test
    void B_本机没跑过这一轮时换券也要成功_券绑共享的轮次号() throws Exception {
        String sessionId = createSessionOnA();
        // 这一轮正在**实例 B** 上跑：开始标记与坑位都在共享存储里，实例 A 的内存里什么都没有
        liveTurns.start(
                AgentWebTestSupport.ALICE,
                sessionId,
                new PlatformLiveTurnState("turn-remote", "instance-b", "急诊科留观人数", System.currentTimeMillis()));
        occupyByRemoteInstance(AgentWebTestSupport.ALICE, sessionId);

        // 实例 A 换券：本机没在跑这一轮，券必须落到状态库里的轮次号上（从前这里会直接失败）
        EntryTicketService.IssuedTicket ticket = chatServiceA.resumeTicket(AgentWebTestSupport.ALICE, sessionId);

        assertThat(ticket.turnId()).isEqualTo("turn-remote");
    }

    @Test
    void A_那一轮还在别的实例上跑_历史显示成进行中而不是中断() throws Exception {
        String sessionId = createSessionOnA();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));
        liveTurns.start(
                AgentWebTestSupport.ALICE,
                sessionId,
                new PlatformLiveTurnState("turn-remote", "instance-b", "急诊科留观人数", System.currentTimeMillis()));
        occupyByRemoteInstance(AgentWebTestSupport.ALICE, sessionId);

        List<Map<String, Object>> turns = historyViaHttp(sessionId);

        // 第 2 轮就是「正在跑的那一轮」：问题亮着、没有 done（进行中）、也没有 error（不是中断）
        assertThat(turns).hasSize(2);
        List<Map<String, Object>> pending = events(turns.get(1));
        assertThat(pending).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("user");
            assertThat(((Map<?, ?>) event.get("data")).get("text")).isEqualTo("急诊科留观人数");
        });
        assertThat(pending).noneSatisfy(event -> assertThat(event.get("name")).isIn("error", "done"));
    }

    @Test
    void A_别的实例跑完了_接流能把这一轮整段补过来() throws Exception {
        String sessionId = createSessionOnA();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));
        liveTurns.start(
                AgentWebTestSupport.ALICE,
                sessionId,
                new PlatformLiveTurnState("turn-remote", "instance-b", "急诊科留观人数", System.currentTimeMillis()));
        occupyByRemoteInstance(AgentWebTestSupport.ALICE, sessionId);

        // 先换券、再拿流：拿流的这一刻会记下「已经跑完几轮」的基线，之后多出来的那一轮才是远端的结果
        String ticket = chatServiceA.resumeTicket(AgentWebTestSupport.ALICE, sessionId).ticket();
        reactor.core.publisher.Flux<StreamRecord> stream = chatServiceA.attach(ticket, -1);
        // 模拟「实例 B 跑完了」：把这一轮写进共享的会话状态（框架在真实运行时里就是这么做的）
        seedConversation(
                sessionId,
                user("心内科门诊量"),
                assistant("上个月 1200 人次。"),
                user("急诊科留观人数"),
                assistant("上个月 36 人次。"));

        List<StreamRecord> records = stream.collectList().block(Duration.ofSeconds(15));

        assertThat(records).isNotNull();
        assertThat(records).extracting(record -> record.event().type().wireName())
                .containsExactly("user", "token", "done");
        assertThat(records.get(0).event().payload()).containsEntry("text", "急诊科留观人数");
        assertThat(records.get(1).event().payload()).containsEntry("delta", "上个月 36 人次。");
    }

    @Test
    void C_实例挂了_另一个实例照样读到完整历史并看到中断说明() throws Exception {
        String sessionId = createSessionOnA();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));
        // 模拟「实例 B 在跑这一轮时被硬杀」：开始标记留下了，坑位没人占（进程没了）
        liveTurns.start(
                AgentWebTestSupport.ALICE,
                sessionId,
                new PlatformLiveTurnState("turn-dead", "instance-b", "急诊科留观人数", 1L));

        List<Map<String, Object>> turns = historyViaHttp(sessionId);

        // 会话与前面那一轮都在（C），没跑完的那一轮有明确的中断说明
        assertThat(turns).hasSize(2);
        assertThat(events(turns.get(1))).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("error");
            assertThat(String.valueOf(((Map<?, ?>) event.get("data")).get("code"))).isEqualTo("TURN_INTERRUPTED");
        });
    }

    private String createSessionOnA() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId");
    }

    private List<Map<String, Object>> historyViaHttp(String sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.turns");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> turn) {
        return (List<Map<String, Object>>) turn.get("events");
    }

    private void seedConversation(String sessionId, Msg... messages) {
        agentStateStore.save(
                AgentWebTestSupport.ALICE,
                sessionId,
                SessionCatalog.AGENT_STATE_KEY,
                AgentState.builder()
                        .sessionId(sessionId)
                        .userId(AgentWebTestSupport.ALICE)
                        .context(List.of(messages))
                        .build());
    }

    private static Msg user(String text) {
        return UserMessage.builder().content(List.of(TextBlock.builder().text(text).build())).build();
    }

    private static Msg assistant(String text) {
        return AssistantMessage.builder().content(List.of(TextBlock.builder().text(text).build())).build();
    }
}
