package com.djzy.assistant.agentweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentweb.session.TurnGateKeys;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 「这一轮开了头但没跑完」在历史里要有交代（T1-08 / T1-09）。
 *
 * <p>要证的是**用户能看到什么**，而不是内部有没有标记。同样是「没跑完」，分两种：
 * <ul>
 *   <li>**已经没人跑了**（实例硬挂）→ 多出一条「上一轮没跑完，请重新发送」，并且**原话还在**；</li>
 *   <li>**还在别的实例上跑** → 多出一条「进行中」的轮次（只显示问题、**没有**结束标记），
 *       这样用户刷新到别的实例上还能看到自己问过什么，前端会接着去接流等结果；
 *       绝不能挂成「中断」，否则会把正在生成的这一轮误报成失败；</li>
 *   <li>跑完的轮次不留标记 → 历史里不许凭空多出一条假轮次。</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:interruptedturn;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "agent-service.secrets.login-token=" + AgentWebTestSupport.JWT_SECRET,
    "agent-service.runtime-id=noop",
    "agent-service.turns-per-minute=100",
    "agent-service.ticket-store=memory",
    "agent-service.rate-limit-store=memory",
    "agent-service.stop-signal-store=memory",
    "agent-service.turn-gate-store=memory",
    // 共享总线也退回内存实现（生产默认 redis）：单测不依赖本机 Redis。
    "agent-service.live-bus=memory",
    "agent-service.event-persist-interval-ms=3600000"
})
@Import(AgentWebH2Config.class)
@AutoConfigureMockMvc
class AgentWebInterruptedTurnTest {

    private static final Path WORK_DIR;

    static {
        try {
            WORK_DIR = Files.createTempDirectory("agent-web-interrupted");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-service.event-log-dir", () -> WORK_DIR.resolve("log").toString());
        registry.add("agent-service.workspace-dir", () -> WORK_DIR.resolve("workspace").toString());
        registry.add("agent-service.instance-id", () -> "interrupted-test");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentStateStore agentStateStore;

    @Autowired
    private PlatformLiveTurnStore liveTurns;

    @Autowired
    private SessionTurnGate turnGate;

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
    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId");
    }

    private List<Map<String, Object>> historyOf(String sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.turns");
    }

    @Test
    void 有开始标记且没人跑_历史里如实说这一轮没跑完并把原话留住() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));
        // 模拟「实例在跑这一轮时被硬杀」：开始标记留下了，坑位也没人占（进程没了）
        liveTurns.start(
                AgentWebTestSupport.ALICE,
                sessionId,
                new PlatformLiveTurnState("turn-dead", "instance-that-died", "急诊科上个月的留观人数", 1L));

        List<Map<String, Object>> turns = historyOf(sessionId);

        assertThat(turns).hasSize(2);
        Map<String, Object> last = turns.get(turns.size() - 1);
        assertThat(events(last)).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("user");
            assertThat(((Map<?, ?>) event.get("data")).get("text")).isEqualTo("急诊科上个月的留观人数");
        });
        assertThat(events(last)).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("error");
            assertThat(String.valueOf(((Map<?, ?>) event.get("data")).get("code"))).isEqualTo("TURN_INTERRUPTED");
        });
    }

    @Test
    void 有开始标记但坑位还在_显示成进行中_不许说成中断() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));
        liveTurns.start(
                AgentWebTestSupport.ALICE,
                sessionId,
                new PlatformLiveTurnState("turn-live", "instance-still-alive", "还在跑的问题", 1L));
        // 别的实例正占着坑位（TTL 内）
        occupyByRemoteInstance(AgentWebTestSupport.ALICE, sessionId);

        List<Map<String, Object>> turns = historyOf(sessionId);

        // 第 2 轮 = 正在跑的那一轮：问题亮出来了，但既没有 error（不是中断）也没有 done（还没跑完）
        assertThat(turns).hasSize(2);
        List<Map<String, Object>> pending = events(turns.get(1));
        assertThat(pending).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("user");
            assertThat(((Map<?, ?>) event.get("data")).get("text")).isEqualTo("还在跑的问题");
        });
        assertThat(pending)
                .noneSatisfy(event -> assertThat(event.get("name")).isIn("error", "done"));
    }

    @Test
    void 跑完的轮次不许凭空多出一条假轮次() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));

        List<Map<String, Object>> turns = historyOf(sessionId);

        assertThat(turns).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> turn) {
        return (List<Map<String, Object>>) turn.get("events");
    }

    private void seedConversation(String sessionId, Msg... messages) {
        agentStateStore.save(
                AgentWebTestSupport.ALICE,
                sessionId,
                com.djzy.assistant.agentweb.session.SessionCatalog.AGENT_STATE_KEY,
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
