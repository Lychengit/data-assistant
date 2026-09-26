package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEvent;
import java.util.Objects;

/**
 * 推给前端的一条 SSE 记录（§19.4 断线续传）。
 *
 * @param seq **会话内**单调递增的流序号，同时作为 SSE 的 {@code id:}——客户端重连时带
 *     {@code Last-Event-ID} 或 {@code afterSeq}，服务端从 {@code seq > after} 处继续
 * @param turnId 这条记录属于哪一轮。有了它，重连时才能**只补当前这一轮**，
 *     而不会把上一轮的卡片又推一遍
 * @param event 业务事件（§12.2）
 * @param timestampMs 记录时刻
 */
public record StreamRecord(long seq, String turnId, SseEvent event, long timestampMs) {

    public StreamRecord {
        Objects.requireNonNull(event, "event");
    }
}
