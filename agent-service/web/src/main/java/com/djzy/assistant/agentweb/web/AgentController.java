package com.djzy.assistant.agentweb.web;

import com.djzy.assistant.agentweb.auth.Authenticator;
import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.service.ChatService;
import com.djzy.assistant.agentweb.session.SessionSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话入口（§12.1 / §19.4）。
 *
 * <p>写操作分两步是刻意的：**消息走 POST 体、令牌走 Authorization 头**，
 * 换来的券（短时、一次性）才允许出现在 SSE 的 query 里——令牌永不进 URL / 日志。
 */
@RestController
@RequestMapping("/v1/agent")
public class AgentController {

    private final ChatService chatService;
    private final Authenticator authenticator;

    public AgentController(ChatService chatService, Authenticator authenticator) {
        this.chatService = chatService;
        this.authenticator = authenticator;
    }

    @PostMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String userId = authenticator.requireUser(authorization);
        String sessionId = chatService.createSession(userId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 我的会话列表（§19.4 历史会话）：最近聊过的在最上面。
     *
     * <p>读接口也走同一个鉴权口：列表就是「我有哪些会话」，userId **只能**从令牌来（§11.3）。
     */
    @GetMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String userId = authenticator.requireUser(authorization);
        return ResponseEntity.ok(Map.of("sessions", chatService.listSessions(userId)));
    }

    /**
     * 切换历史会话：把这个会话发生过的事件按轮次回放出来（§19.4）。
     *
     * <p>直接回放事件而不是回放「界面字段」：前端用与实时流**同一个**归约器渲染，
     * 历史会话和刚刚答完的那一轮长得一模一样。不是自己的会话一律 404（不泄露存在性，§11.3）。
     */
    @GetMapping(path = "/sessions/{sessionId}/turns", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> history(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId) {
        String userId = authenticator.requireUser(authorization);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("turns", chatService.history(userId, sessionId));
        return ResponseEntity.ok(body);
    }

    /**
     * 归档 / 取消归档（§19.4）。
     *
     * <p>它是**逻辑标记，不是删除**：归档后会话仍在回放位里，历史照样能回放、取消归档就能接着聊
     * （规格把归档与召回列为后置能力，记录长期保留，§8.3 / §20.5）。
     *
     * <p>不带 body 即归档，{@code {"archived": false}} 即取消归档。不是自己的会话一律 404
     * （不泄露存在性，§11.3）。
     */
    @PostMapping(path = "/sessions/{sessionId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> archive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId,
            @RequestBody(required = false) ArchiveRequest request) {
        String userId = authenticator.requireUser(authorization);
        // 缺省是「归档」：POST 这个入口的默认意图就是归档，取消归档要显式说 archived=false
        boolean archived = request == null || !Boolean.FALSE.equals(request.archived());
        return ResponseEntity.ok(Map.of("session", chatService.archive(userId, sessionId, archived)));
    }

    /** 发起一轮：立刻返回券，答案在后台继续生成（断线不影响，§19.4）。 */
    @PostMapping(path = "/sessions/{sessionId}/turns", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> startTurn(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId,
            @RequestBody(required = false) TurnRequest request) {
        String userId = authenticator.requireUser(authorization);
        String token = authenticator.requireToken(authorization);
        TurnRequest safe = request == null ? new TurnRequest(null, null) : request;
        EntryTicketService.IssuedTicket issued = chatService.startTurn(
                userId, token, sessionId, safe.text(), safe.attachments() == null ? List.of() : safe.attachments());
        return ResponseEntity.ok(ticketBody(issued));
    }

    /**
     * 重连换券（§19.4）：**不发起新轮次**，只换一张绑同一会话的券回来接着看。
     *
     * <p>券是一次性的，浏览器 {@code EventSource} 的原生自动重连会拿已核销的旧券撞 401，
     * 所以「刷新页面 / 断网重连接着看」走这个入口：客户端带上最后收到的 seq（{@code afterSeq}）重连。
     */
    @PostMapping(path = "/sessions/{sessionId}/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> resumeTicket(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId) {
        String userId = authenticator.requireUser(authorization);
        EntryTicketService.IssuedTicket issued = chatService.resumeTicket(userId, sessionId);
        return ResponseEntity.ok(ticketBody(issued));
    }

    /** HITL 确认（§19.9）：消费确认结果并续跑，返回续跑流所需的券。 */
    @PostMapping(path = "/sessions/{sessionId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId,
            @RequestBody(required = false) ConfirmRequest request) {
        String userId = authenticator.requireUser(authorization);
        String token = authenticator.requireToken(authorization);
        if (request == null || request.confirmId() == null || request.confirmId().isBlank()) {
            throw ApiException.badRequest("缺少 confirmId");
        }
        EntryTicketService.IssuedTicket issued =
                chatService.confirm(userId, token, sessionId, request.confirmId(), Boolean.TRUE.equals(request.approved()));
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
