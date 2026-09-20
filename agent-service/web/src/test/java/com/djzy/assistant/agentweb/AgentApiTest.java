package com.djzy.assistant.agentweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 接入层 HTTP 契约（§12.2 / §19.4）：令牌进出、券一次性、SSE 线格式、断线续传。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:agentweb;DATABASE_TO_LOWER=TRUE;MODE=PostgreSQL",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "agent-service.secrets.login-token=" + AgentWebTestSupport.JWT_SECRET,
    "agent-service.runtime-id=noop",
    "agent-service.turns-per-minute=100",
    // 落库由测试自己驱动（下面手动 drainOnce），别让定时任务和断言抢
    "agent-service.event-persist-interval-ms=3600000"
})
@Import(AgentWebH2Config.class)
@AutoConfigureMockMvc
class AgentApiTest {

    private static final Path WORK_DIR;

    static {
        try {
            WORK_DIR = Files.createTempDirectory("agent-web-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-service.event-log-dir", () -> WORK_DIR.resolve("log").toString());
        registry.add("agent-service.state-dir", () -> WORK_DIR.resolve("state").toString());
        registry.add("agent-service.instance-id", () -> "test");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.djzy.assistant.common.bus.EventPersistConsumer eventPersistConsumer;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.djzy.assistant.common.eventlog.AppendOnlyEventLog eventLog;

    @Autowired
    private com.djzy.assistant.agentweb.session.ChatSessionRegistry registry;

    @Autowired
    private com.djzy.assistant.agentweb.session.SessionJournal journal;

    /** HITL 回执（§19.9）：返回续跑流所需的券。 */
    private String confirm(String sessionId, String confirmId, boolean approved) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmId\":\"" + confirmId + "\",\"approved\":" + approved + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
    }

    @Test
    void 没有令牌不许建会话() throws Exception {
        mockMvc.perform(post("/v1/agent/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void 已停用账号的令牌立即失效() throws Exception {
        mockMvc.perform(post("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.GONE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 轮次返回一次性券_并且SSE按契约下发() throws Exception {
        String sessionId = createSession();
        String ticket = startTurn(sessionId, "你好");

        String body = stream(ticket, null);

        assertThat(body).contains("id:0").contains("event:session").contains("event:done");
        assertThat(body).contains("\"sessionId\":\"" + sessionId + "\"");
        // 令牌绝不能出现在响应里（§19.4）
        assertThat(body).doesNotContain(AgentWebTestSupport.token(AgentWebTestSupport.ALICE));
    }

    @Test
    void 券用过一次就作废() throws Exception {
        String sessionId = createSession();
        String ticket = startTurn(sessionId, "你好");
        assertThat(stream(ticket, null)).contains("event:done");

        mockMvc.perform(get("/v1/agent/chat/stream").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带LastEventId重连只推后面的() throws Exception {
        String sessionId = createSession();
        String ticketOne = startTurn(sessionId, "你好");
        String full = stream(ticketOne, null);
        assertThat(full).contains("id:0");

        String ticketTwo = startTurn(sessionId, "再问一次");
        String tail = stream(ticketTwo, "0");

        assertThat(tail).doesNotContain("event:session");
        assertThat(tail).contains("id:1");
    }

    @Test
    void 空消息被拒() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 别人的会话号当作不存在() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"偷看\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 刷新页面靠换券重连_只补没看过的那一段() throws Exception {
        String sessionId = createSession();
        String first = startTurn(sessionId, "你好");
        assertThat(stream(first, null)).contains("id:0").contains("event:done");

        // 券是一次性的：重连必须换一张新的，而不是复用旧券
        String resumed = resumeTicket(sessionId, AgentWebTestSupport.ALICE);
        String tail = stream(resumed, null, "0");
        assertThat(tail).doesNotContain("event:session");
        assertThat(tail).contains("id:1");

        mockMvc.perform(get("/v1/agent/chat/stream").param("ticket", resumed))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 换券不会发起新轮次() throws Exception {
        String sessionId = createSession();
        String ticket = startTurn(sessionId, "你好");
        stream(ticket, null);

        // 换券只换连接位置，不产生新的一轮：seq 不会因为换券而增长
        String resumed = resumeTicket(sessionId, AgentWebTestSupport.ALICE);
        String tail = stream(resumed, null, "0");
        assertThat(tail).doesNotContain("event:intent").doesNotContain("event:final");
        assertThat(tail).contains("event:done");
    }

    @Test
    void 别人的会话换不到券() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/tickets")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.BOB)))
                .andExpect(status().isNotFound());
    }

    /** §19.4：历史会话列表——标题就是首条提问，而且只有自己的会话看得见。 */
    @Test
    void 会话列表只有自己的_标题取首条提问() throws Exception {
        String aliceSession = createSession();
        stream(startTurn(aliceSession, "心内科门诊量"), null);
        String bobSession = createSession(AgentWebTestSupport.BOB);
        stream(startTurn(bobSession, "bob 的私事", AgentWebTestSupport.BOB), null);

        MvcResult result = mockMvc.perform(get("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> sessions =
                com.jayway.jsonpath.JsonPath.read(utf8(result.getResponse()), "$.sessions");
        assertThat(sessions).extracting(row -> row.get("sessionId")).contains(aliceSession).doesNotContain(bobSession);

        Map<String, Object> mine = sessions.stream()
                .filter(row -> aliceSession.equals(row.get("sessionId")))
                .findFirst()
                .orElseThrow();
        assertThat(mine.get("title")).isEqualTo("心内科门诊量");
        assertThat(mine.get("questions")).isEqualTo(1);
    }

    /** §19.4：切回历史会话——服务端回放的是原始事件，前端用同一个归约器渲染。 */
    @Test
    void 历史会话按轮次回放() throws Exception {
        String sessionId = createSession();
        stream(startTurn(sessionId, "心内科门诊量"), null);

        MvcResult result = mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> turns =
                com.jayway.jsonpath.JsonPath.read(utf8(result.getResponse()), "$.turns");
        assertThat(turns).hasSize(1);

        List<Map<String, Object>> events =
                com.jayway.jsonpath.JsonPath.read(utf8(result.getResponse()), "$.turns[0].events");
        assertThat(events).extracting(event -> event.get("name")).contains("user", "done");

        Map<String, Object> userEvent = events.stream()
                .filter(event -> "user".equals(event.get("name")))
                .findFirst()
                .orElseThrow();
        // 提问正文必须能回放出来：否则切回历史会话只剩答案、没有上下文
        @SuppressWarnings("unchecked")
        Map<String, Object> userData = (Map<String, Object>) userEvent.get("data");
        assertThat(userData).containsEntry("text", "心内科门诊量");
    }

    /**
     * §19.4 归档：**逻辑标记，不是删除**。
     *
     * <p>三条一起才说明归档没偷偷变成删除：① 列表里还在（只是带归档标记）；② 历史照样能回放；
     * ③ 取消归档就能回到「在用」。
     */
    @Test
    void 归档是逻辑标记_不是删除() throws Exception {
        String sessionId = createSession();
        stream(startTurn(sessionId, "心内科门诊量"), null);

        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.archived").value(true));

        // ① 没被删：还在列表里，带归档标记
        assertThat(sessionRow(sessionId)).containsEntry("archived", true);
        // ② 历史照样回放：归档收起来的是列表位置，不是内容
        mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].events").isNotEmpty());

        // ③ 取消归档就回到「在用」：归档不是单向闸门
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.archived").value(false));
        assertThat(sessionRow(sessionId)).containsEntry("archived", false);
    }

    /** 归档落在唯一事实源里：重启（新的日志实例）后归档位还在，不是只记在内存。 */
    @Test
    void 归档位写在日志里_重启后仍在() throws Exception {
        String sessionId = createSession();
        stream(startTurn(sessionId, "心内科门诊量"), null);
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .andExpect(status().isOk());

        // 直接读回放位（与进程重启后重建列表走的是同一条解析路径）
        assertThat(journal.listSessions(AgentWebTestSupport.ALICE))
                .filteredOn(summary -> sessionId.equals(summary.sessionId()))
                .extracting(com.djzy.assistant.agentweb.session.SessionSummary::archived)
                .containsExactly(true);
    }

    /** §11.3：能归档别人的会话就等于确认了它存在，所以同样是 404。 */
    @Test
    void 别人的会话不能归档() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .andExpect(status().isNotFound());
    }

    /** §11.3：别人的会话连「存在」都不能被确认，所以是 404 而不是 403。 */
    @Test
    void 别人的会话历史一律404() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(get("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.BOB)))
                .andExpect(status().isNotFound());
    }

    /** §19.6：用户拿答案的路径上不落库；事件先入队，再由 event-persist 攒批搬进事实表。 */
    @Test
    void 一轮结束后事件入队_由消费者攒批落进事实表() throws Exception {
        String sessionId = createSession();
        String ticket = startTurn(sessionId, "你好");
        stream(ticket, null);

        // 日志先行（§19.6）：事件先落到本地 append-only 日志（唯一事实源），再进队列
        eventLog.flush();
        String log = Files.readString(eventLog.config().logFile());
        assertThat(log).contains(sessionId);

        int rows = eventPersistConsumer.drainOnce();
        assertThat(rows).isGreaterThan(0);

        List<Map<String, Object>> turns = new JdbcTemplate(dataSource)
                .queryForList("SELECT turn_id, user_input, trace_id FROM conversation_turn WHERE session_id = ?", sessionId);
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).get("turn_id")).isNotNull();
        // 用户说的话必须能从事实表重建（回放 / 评估都读它）
        assertThat(turns.get(0).get("user_input")).isEqualTo("你好");

        // 已确认的不再重复搬（at-least-once + event_id 幂等）
        assertThat(new JdbcTemplate(dataSource)
                        .queryForObject("SELECT count(*) FROM event_outbox WHERE delivered_at IS NULL", Integer.class))
                .isZero();
        assertThat(turns).hasSize(1);
    }

    /** §19.9 / §12.1：需要确认的操作**挂在对话气泡里**（confirm 事件），挂起快照落 PG，确认之后接着跑同一轮。 */
    @Test
    void 写操作挂起后确认续跑_挂起状态落库且确认只能用一次() throws Exception {
        String sessionId = createSession();
        assertThat(stream(startTurn(sessionId, "帮我登记查询"), null))
                .contains("event:confirm")
                .contains("c-test-1")
                .contains("event:done");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_state WHERE session_id = ?", Integer.class, sessionId))
                .isEqualTo(1);

        String ticket = confirm(sessionId, "c-test-1", true);
        assertThat(stream(ticket, null)).contains("已执行");

        // 一轮正常跑完就清掉挂起位：不留着让下一轮误认成待确认
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_state WHERE session_id = ?", Integer.class, sessionId))
                .isZero();

        // 确认是消费型：同一个 confirmId 再确认一次就是 409（§19.9 不可绕过、不可重放）
        mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmId\":\"c-test-1\",\"approved\":true}"))
                .andExpect(status().isConflict());
    }

    /** §19.5：进程重启 / 换 pod 之后，运行时会话句柄没了，靠 PG 里的挂起快照把会话接回来再续跑。 */
    @Test
    void 运行时句柄丢失后_从PG挂起快照接回来续跑() throws Exception {
        String sessionId = createSession();
        stream(startTurn(sessionId, "帮我登记查询"), null);

        // 模拟换 pod：内存里的运行时句柄清掉，只留 PG 里的快照
        registry.find(sessionId, AgentWebTestSupport.ALICE).clearRuntimeSession();

        assertThat(stream(confirm(sessionId, "c-test-1", true), null)).contains("已执行");
    }

    /** 列表里这一行（会点开列表接口拿，而不是信归档接口的返回值）。 */
    private Map<String, Object> sessionRow(String sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(AgentWebTestSupport.ALICE)))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> sessions =
                com.jayway.jsonpath.JsonPath.read(utf8(result.getResponse()), "$.sessions");
        return sessions.stream()
                .filter(row -> sessionId.equals(row.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("会话不在列表里：" + sessionId));
    }

    private String createSession() throws Exception {
        return createSession(AgentWebTestSupport.ALICE);
    }

    private String createSession(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId");
    }

    private String startTurn(String sessionId, String text) throws Exception {
        return startTurn(sessionId, text, AgentWebTestSupport.ALICE);
    }

    private String startTurn(String sessionId, String text, String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/turns")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
    }

    private String resumeTicket(String sessionId, String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/agent/sessions/" + sessionId + "/tickets")
                        .header(HttpHeaders.AUTHORIZATION, AgentWebTestSupport.bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
    }

    /** 打开 SSE 连接并取回线格式正文（MockMvc 下异步是否已启动由容器决定，两种都兼容）。 */
    private String stream(String ticket, String lastEventId) throws Exception {
        return stream(ticket, lastEventId, null);
    }

    /** @param afterSeq 显式续传起点（换券重连用；新起的 EventSource 带不了 Last-Event-ID 头） */
    private String stream(String ticket, String lastEventId, String afterSeq) throws Exception {
        var builder = get("/v1/agent/chat/stream").param("ticket", ticket);
        if (lastEventId != null) {
            builder = builder.header("Last-Event-ID", lastEventId);
        }
        if (afterSeq != null) {
            builder = builder.param("afterSeq", afterSeq);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return utf8(mockMvc.perform(asyncDispatch(result)).andReturn().getResponse());
        }
        return utf8(result.getResponse());
    }

    /** 正文按 UTF-8 解：SSE 按规范就是 UTF-8，但 MockMvc 默认会按 ISO-8859-1 读，中文会变乱码。 */
    private static String utf8(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        return new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
