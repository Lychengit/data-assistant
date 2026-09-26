package com.djzy.assistant.agentweb.web;

import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.service.ChatService;
import com.djzy.assistant.agentweb.session.SessionSummary;
import com.djzy.assistant.common.web.auth.CurrentUser;
import com.djzy.assistant.common.web.auth.UserContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话入口（§12.1 / §19.4）。
 *
 * <p>写操作分两步是刻意的：**消息走 POST 体、令牌走 Authorization 头**，
 * 换来的券（短时、一次性）才允许出现在 SSE 的 query 里——令牌永不进 URL / 日志。
 *
 * <p>身份（userId / 令牌）**不在这里解析**：它由 {@code UserContextInterceptor} 在进控制器之前
 * 统一验好并挂到当前线程上，所以这里只声明 {@code @CurrentUser} 参数取用即可。
 */
@RestController
@RequestMapping("/v1/agent")
public class AgentController {

    private final ChatService chatService;

    public AgentController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createSession(@CurrentUser String userId) {
        String sessionId = chatService.createSession(userId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 我的会话列表（§19.4 历史会话）：最近聊过的在最上面。
     *
     * <p>读接口也走同一个鉴权口：列表就是「我有哪些会话」，userId **只能**从令牌来（§11.3）。
     */
    @GetMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listSessions(@CurrentUser String userId) {
        return ResponseEntity.ok(Map.of("sessions", chatService.listSessions(userId)));
    }

    /**
     * 切换历史会话：把会话内容按轮次翻译成事件回放出来（§19.4）。
     *
     * <p>内容来自**框架的会话状态**（就是模型看见过的那串消息），不是另记的一份台账——
     * 于是「用户看到的历史」与「模型记得的上下文」天然是同一份，不会各说各话。
     *
     * <p>回放的是事件而不是「界面字段」：前端用与实时流**同一个**归约器渲染，
     * 历史会话和刚刚答完的那一轮长得一模一样。不是自己的会话一律 404（不泄露存在性，§11.3）。
     */
    @GetMapping(path = "/sessions/{sessionId}/turns", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> history(
            @CurrentUser String userId, @PathVariable String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("turns", chatService.history(userId, sessionId));
        return ResponseEntity.ok(body);
    }

    /**
     * 归档 / 取消归档（§19.4）。
     *
     * <p>它是**逻辑标记，不是删除**：归档只是把会话从列表里收起来，历史照样能回放、取消归档就能接着聊
     * （规格把归档与召回列为后置能力，记录长期保留，§8.3 / §20.5）。
     *
     * <p>不带 body 即归档，{@code {"archived": false}} 即取消归档。不是自己的会话一律 404
     * （不泄露存在性，§11.3）。
     */
    @PostMapping(path = "/sessions/{sessionId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> archive(
            @CurrentUser String userId,
            @PathVariable String sessionId,
            @RequestBody(required = false) ArchiveRequest request) {
        // 缺省是「归档」：POST 这个入口的默认意图就是归档，取消归档要显式说 archived=false
        boolean archived = request == null || !Boolean.FALSE.equals(request.archived());
        return ResponseEntity.ok(Map.of("session", chatService.archive(userId, sessionId, archived)));
    }

    /** 发起一轮：立刻返回券，答案在后台继续生成（断线不影响，§19.4）。 */
    @PostMapping(path = "/sessions/{sessionId}/turns", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> startTurn(
            @CurrentUser UserContext userContext,
            @PathVariable String sessionId,
            @RequestBody(required = false) TurnRequest request) {
        TurnRequest safe = request == null ? new TurnRequest(null, null) : request;
        // 令牌从用户上下文里取：agent 调网关时要原样透传，agent 自己不用它读权限（§20.1.6-5）
        EntryTicketService.IssuedTicket issued = chatService.startTurn(
                userContext.userId(),
                userContext.bearerToken(),
                sessionId,
                safe.text(),
                safe.attachments() == null ? List.of() : safe.attachments());
        return ResponseEntity.ok(ticketBody(issued));
    }

    /**
     * 停止当前这一轮（§19.4）。
     *
     * <p>幂等，且**跨实例**有效：请求打到哪台机器都能停下这一轮（信号走共享存储，
     * 真正在跑的实例会看到它），详见 {@link ChatService#stop}。
     * 已经产出的内容不会丢，会话还在，接着问下一轮即可。
     */
    @PostMapping(path = "/sessions/{sessionId}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> stop(
            @CurrentUser String userId, @PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.stop(userId, sessionId));
    }

    /**
     * 重连换券（§19.4）：**不发起新轮次**，只换一张绑同一会话的券回来接着看。
     *
     * <p>券是一次性的，浏览器 {@code EventSource} 的原生自动重连会拿已核销的旧券撞 401，
     * 所以「刷新页面 / 断网重连接着看」走这个入口：客户端带上最后收到的 seq（{@code afterSeq}）重连。
     *
     * <p>注意 seq 只在**同一条活连接**的语义里有意义（它由处理这一轮的那台实例分配）。
     * 换实例、或服务重启过之后，客户端手上的 seq 会失效——服务端认得出这种情况，
     * 会按「从这一轮开头补」处理，而不是拿一个陌生游标把整轮回答静默滤掉。
     */
    @PostMapping(path = "/sessions/{sessionId}/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> resumeTicket(
            @CurrentUser String userId, @PathVariable String sessionId) {
        EntryTicketService.IssuedTicket issued = chatService.resumeTicket(userId, sessionId);
        return ResponseEntity.ok(ticketBody(issued));
    }

    /** HITL 确认（§19.9）：消费确认结果并续跑，返回续跑流所需的券。 */
    @PostMapping(path = "/sessions/{sessionId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @CurrentUser UserContext userContext,
            @PathVariable String sessionId,
            @RequestBody(required = false) ConfirmRequest request) {
        if (request == null || request.confirmId() == null || request.confirmId().isBlank()) {
            throw ApiException.badRequest("缺少 confirmId");
        }
        EntryTicketService.IssuedTicket issued = chatService.confirm(
                userContext.userId(),
                userContext.bearerToken(),
                sessionId,
                request.confirmId(),
                Boolean.TRUE.equals(request.approved()));
        return ResponseEntity.ok(ticketBody(issued));
    }

    private static Map<String, Object> ticketBody(EntryTicketService.IssuedTicket issued) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", issued.sessionId());
        body.put("turnId", issued.turnId());
        body.put("ticket", issued.ticket());
        body.put("expiresAt", issued.expiresAt().toString());
        body.put("streamUrl", "/v1/agent/chat/stream?ticket=" + issued.ticket());
        return body;
    }

    public record TurnRequest(String text, List<String> attachments) {}

    public record ArchiveRequest(Boolean archived) {}

    public record ConfirmRequest(String confirmId, Boolean approved) {}
}
