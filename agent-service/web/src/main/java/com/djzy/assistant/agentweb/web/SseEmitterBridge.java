package com.djzy.assistant.agentweb.web;

import com.djzy.assistant.agentweb.session.JournalRecord;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * {@link Flux} → {@link SseEmitter} 的桥（§12.2 线格式）。
 *
 * <p>线格式：{@code id: <seq>} 让浏览器重连时自动带 {@code Last-Event-ID}；
 * {@code event: <业务事件名>} 就是 §12.2 的事件契约；{@code data:} 是该事件的 JSON 载荷。
 *
 * <p>心跳是必需的：一轮回答可能跑几十秒，中间没有字节的 SSE 连接会被反向代理掐断，
 * 而「被掐断」在前端看来和「回答完了」没有区别。
 */
final class SseEmitterBridge {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterBridge.class);
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private static final long HEARTBEAT_SECONDS = 15L;

    private SseEmitterBridge() {}

    static SseEmitter bridge(Flux<JournalRecord> records, Duration timeout) {
        SseEmitter emitter = new SseEmitter(Math.max(1_000L, timeout.toMillis()));
        AtomicBoolean closed = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = HEARTBEAT.scheduleAtFixedRate(
                () -> beat(emitter, closed), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        Disposable subscription = records.subscribe(
                record -> send(emitter, record, closed),
                error -> {
                    log.warn("SSE 流异常关闭", error);
                    close(emitter, closed, heartbeat);
                    emitter.completeWithError(error);
                },
                () -> {
                    close(emitter, closed, heartbeat);
                    emitter.complete();
                });
        emitter.onCompletion(() -> {
            subscription.dispose();
            close(emitter, closed, heartbeat);
        });
        emitter.onTimeout(() -> {
            subscription.dispose();
            close(emitter, closed, heartbeat);
        });
        emitter.onError(error -> {
            subscription.dispose();
            close(emitter, closed, heartbeat);
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, JournalRecord record, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(record.seq()))
                    .name(record.event().type().wireName())
                    .data(record.event().payload(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 前端断开是常态（换页 / 刷新）：这里只需停止推送，答案继续在后台生成并进入回放位。
            log.debug("SSE 推送终止（客户端已断开）：seq={}", record.seq());
            closed.set(true);
        }
    }

    private static void beat(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("hb"));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }

    private static void close(SseEmitter emitter, AtomicBoolean closed, ScheduledFuture<?> heartbeat) {
        closed.set(true);
        heartbeat.cancel(false);
    }
}
