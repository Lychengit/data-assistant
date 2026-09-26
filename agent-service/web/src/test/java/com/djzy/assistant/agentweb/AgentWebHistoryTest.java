package com.djzy.assistant.agentweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.djzy.assistant.agentweb.session.SessionCatalog;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 历史会话这条链的端到端验证（§19.4 / §19.5）：**内容只有一个来源——框架的会话状态**。
 *
 * <p>为什么单独一个用例类、而且自己往状态库里写对话：跑单测用的 noop 运行时是纯契约假实现，
 * 它不存会话状态（真实运行时才会存）。把「状态里有一段对话」直接摆进去，
 * 测的就正好是平台这一侧的投影与鉴权，不被运行时的行为牵着走。
 *
 * <p>它同时守住了一件容易悄悄坏掉的事：**换台实例（新的进程、新的内存）也读得到同一份历史**——
 * 这条测试里没有任何进程内的会话句柄，全部从共享状态库读。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:agentwebhistory;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "agent-service.secrets.login-token=" + AgentWebTestSupport.JWT_SECRET,
    "agent-service.runtime-id=noop",
    "agent-service.turns-per-minute=100",
    "agent-service.ticket-store=memory",
    "agent-service.rate-limit-store=memory",
    "agent-service.stop-signal-store=memory",
    // 轮次闸门也显式钉回内存：单测不依赖本机 Redis，多副本口径由 RedisSessionTurnGateTest 验
    "agent-service.turn-gate-store=memory",
    // 共享总线也退回内存实现（生产默认 redis）：单测不依赖本机 Redis。
    "agent-service.live-bus=memory",
    "agent-service.event-persist-interval-ms=3600000"
})
@Import(AgentWebH2Config.class)
@AutoConfigureMockMvc
class AgentWebHistoryTest {

    private static final Path WORK_DIR;

    static {
        try {
            WORK_DIR = Files.createTempDirectory("agent-web-history");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-service.event-log-dir", () -> WORK_DIR.resolve("log").toString());
        registry.add("agent-service.workspace-dir", () -> WORK_DIR.resolve("workspace").toString());
        registry.add("agent-service.instance-id", () -> "history-test");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentStateStore agentStateStore;

    /** 建会话（走真实接口，顺带把会话档案落进状态库）。 */
    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId");
    }

    /** 摆一段对话进状态库：真实运行时（AgentScope）每轮结束也会写同样的东西。 */
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

    @Test
    void 列表的标题与提问条数来自提问那一刻的记录() throws Exception {
        String sessionId = createSession();
        ask(sessionId, "心内科门诊量");

        Map<String, Object> mine = rowOf(sessionId);
        assertThat(mine.get("title")).isEqualTo("心内科门诊量");
        assertThat(mine.get("questions")).isEqualTo(1);
        assertThat(mine.get("archived")).isEqualTo(false);
    }

    /**
     * H-13：会话列表只认「提问时顺手写下的小档案」，**不回头读对话正文**。
     *
     * <p>这条用例专门往状态库里塞了一段真实对话、却没走提问入口，列表因此仍显示「未命名 / 0 条」。
     * 它守住的是「列表不读正文」这个口径：哪天有人把列表改回去读正文，这里就会红。
     * 顺带说明两条路互不干扰——正文还在，历史回放照样有一轮。
     */
    @Test
    void 列表不读对话正文() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));

        Map<String, Object> mine = rowOf(sessionId);
        assertThat(mine.get("title")).isEqualTo("未命名会话");
        assertThat(mine.get("questions")).isEqualTo(0);
        assertThat(turns(sessionId)).hasSize(1);
    }

    @Test
    void 切回历史会话按轮次回放_提问与答案都在() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));

        List<Map<String, Object>> turns = turns(sessionId);
        assertThat(turns).hasSize(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) turns.get(0).get("events");
        assertThat(events).extracting(event -> event.get("name")).containsExactly("user", "token", "done");

        @SuppressWarnings("unchecked")
        Map<String, Object> userData = (Map<String, Object>) events.get(0).get("data");
        assertThat(userData).containsEntry("text", "心内科门诊量");
    }

    @Test
    void 归档后列表标归档_但历史照样能回放() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));

        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .andExpect(status().isOk());

        Map<String, Object> mine = sessions().stream()
                .filter(row -> sessionId.equals(row.get("sessionId")))
                .findFirst()
                .orElseThrow();
        assertThat(mine.get("archived")).isEqualTo(true);
        // 归档是「从列表里收起来」，不是删除内容
        assertThat(turns(sessionId)).hasSize(1);
    }

    /** §11.3：别人的会话连「存在」都不该被确认，所以是 404。 */
    @Test
    void 别人的会话看不了历史也归档不了() throws Exception {
        String sessionId = createSession();
        seedConversation(sessionId, user("心内科门诊量"), assistant("上个月 1200 人次。"));

        mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.BOB)))
                .andExpect(status().isNotFound());
    }

    /** 提问（走真实接口，不订阅流）：会话列表要的三个数就是在这里顺手写下的。 */
    private void ask(String sessionId, String text) throws Exception {
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk());
    }

    /** 取列表里属于这个会话的那一行。 */
    private Map<String, Object> rowOf(String sessionId) throws Exception {
        return sessions().stream()
                .filter(row -> sessionId.equals(row.get("sessionId")))
                .findFirst()
                .orElseThrow();
    }

    private List<Map<String, Object>> sessions() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sessions");
    }

    private List<Map<String, Object>> turns(String sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.turns");
    }

    private static Msg user(String text) {
        return new UserMessage(text);
    }

    private static Msg assistant(String text) {
        return Msg.builderForRole(MsgRole.ASSISTANT).textContent(text).build();
    }
}
