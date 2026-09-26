package com.djzy.assistant.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * 链路 id（trace id）的取用规则：一次请求尽量用同一个 id，日志才能串成一条线。
 *
 * <p>调用方带了 {@code X-Trace-Id} 就沿用（上游既然起了链路，就别另起一个），
 * 没带就现生成一个——**永远不返回 null**，日志里就不会留下空占位。
 */
public final class RequestTracing {

    /** 链路 id 的请求头名。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    private RequestTracing() {}

    public static String traceId(HttpServletRequest request) {
        String header = request.getHeader(TRACE_HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}
