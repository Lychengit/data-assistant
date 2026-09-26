package com.djzy.assistant.agentweb.web;

import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import com.djzy.assistant.agentweb.service.ChatService;
import com.djzy.assistant.common.web.auth.AnonymousAccess;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 接入（§12.2 / §19.4）：{@code GET /v1/agent/chat/stream?ticket=...}。
 *
 * <p>连接靠**一次性券**建立（券在 URL 里可以，令牌不行）；重连时浏览器自动带
 * {@code Last-Event-ID}，我们从那条之后继续推——刷新页面不会丢答案，也不会重复播一遍。
 */
@RestController
public class ChatStreamController {

    private final ChatService chatService;
    private final AgentServiceProperties properties;

    public ChatStreamController(ChatService chatService, AgentServiceProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
    }

    // 明确声明 charset：EventSource 按规范就是 UTF-8，但 curl / 中间代理 / 日志工具需要这一句才不乱码。
    @AnonymousAccess // 靠一次性券进门，不验 JWT；券本身在 ChatService.attach 里校验
    @GetMapping(path = "/v1/agent/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(
            @RequestParam(value = "ticket", required = false) String ticket,
            @RequestParam(value = "afterSeq", required = false) Long afterSeq,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return SseEmitterBridge.bridge(chatService.attach(ticket, resumeFrom(afterSeq, lastEventId)), properties.getStreamTimeout());
    }

    /**
     * 续传起点：显式 {@code afterSeq} 优先，其次浏览器自动带的 {@code Last-Event-ID}。
     *
     * <p>{@code afterSeq} 是给「换券重连」用的——新起的 {@code EventSource} 带不了
     * {@code Last-Event-ID} 头，所以允许把 seq 放在 query 里：它是个位置，不是凭证，进 URL 无妨（§19.4 只禁令牌）。
     * 两者都缺/非法时给 {@code -1}（宁可多回放，不可留空洞）。
     */
    private static long resumeFrom(Long afterSeq, String lastEventId) {
        return afterSeq != null && afterSeq >= 0 ? afterSeq : parseSeq(lastEventId);
    }

    /** {@code Last-Event-ID} 缺失 / 非法一律从头回放（宁可多回放，不可留空洞）。 */
    private static long parseSeq(String lastEventId) {
        return Optional.ofNullable(lastEventId)
                .flatMap(value -> {
                    try {
                        return Optional.of(Long.parseLong(value.trim()));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElse(-1L);
    }
}
