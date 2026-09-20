package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEvent;
import java.util.Objects;

/**
 * SSE 回放位上的一条记录（§19.4 断线续传）。
 *
 * @param seq **会话内**单调递增的流序号，同时作为 SSE 的 {@code id:}——客户端重连时带
 *     {@code Last-Event-ID}，服务端从 {@code seq > after} 处继续
 * @param event 业务事件（§12.2）
 * @param timestampMs 记录时刻
 */
public record JournalRecord(long seq, SseEvent event, long timestampMs) {

    public JournalRecord {
        Objects.requireNonNull(event, "event");
    }
}
