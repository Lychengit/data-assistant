package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.spi.AgentSession;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 一次对话会话的接入层状态：**会话内单调 seq + 回放缓冲 + 当前运行时句柄**（§19.4）。
 *
 * <p>为什么 seq 由接入层分配而不是复用运行时的事件 seq：运行时的事件 seq 是**每轮从 0 开始**的，
 * 而 SSE 的 {@code Last-Event-ID} 必须跨轮、跨重连单调，否则刷新页面就会重放上一轮。
 *
 * <p>回放缓冲（{@code replay().limit(...)}）同时承担「热回放」与「实时推送」两件事，
 * 因此重连时不会出现「回放完了、实时还没订阅上」的空档。
 */
public final class ChatSession {

    private final String sessionId;
    private final String userId;
    private final SessionJournal journal;
    private final Sinks.Many<JournalRecord> sink;
    private final AtomicLong seq = new AtomicLong(-1);

    private volatile boolean running;
    private volatile boolean suspended;
    private volatile String currentTurnId;
    private volatile AgentSession runtimeSession;
    private volatile long lastTouchedMs = System.currentTimeMillis();

    ChatSession(String sessionId, String userId, SessionJournal journal, int replayBufferSize) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.journal = journal;
        this.sink = Sinks.many().replay().limit(replayBufferSize <= 0 ? 4096 : replayBufferSize);
    }

    public String sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    public boolean running() {
        return running;
    }

    public boolean suspended() {
        return suspended;
    }

    public String currentTurnId() {
        return currentTurnId;
    }

    public AgentSession runtimeSession() {
        return runtimeSession;
    }

    public long lastTouchedMs() {
        return lastTouchedMs;
    }

    void touch() {
        this.lastTouchedMs = System.currentTimeMillis();
    }

    public void beginTurn(String turnId, AgentSession runtimeSession) {
        this.currentTurnId = turnId;
        this.runtimeSession = runtimeSession;
        this.running = true;
        this.suspended = false;
        touch();
    }

    public void endTurn() {
        this.running = false;
        touch();
    }

    public void markSuspended() {
        this.suspended = true;
        this.running = false;
        touch();
    }

    public void clearRuntimeSession() {
        this.runtimeSession = null;
        touch();
    }

    /** 落回放位（先落再推）并推给所有订阅者。 */
    public JournalRecord publish(SseEvent event) {
        synchronized (this) {
            JournalRecord record = new JournalRecord(seq.incrementAndGet(), event, System.currentTimeMillis());
            journal.append(userId, sessionId, record);
            sink.tryEmitNext(record);
            touch();
            return record;
        }
    }

    /** 进程重启后把已有回放位灌回内存缓冲：只回灌、不重写日志（否则日志会被自己复制）。 */
    void restore(List<JournalRecord> records) {
        synchronized (this) {
            for (JournalRecord record : records) {
                seq.updateAndGet(current -> Math.max(current, record.seq()));
                sink.tryEmitNext(record);
            }
        }
    }

    /**
     * @param turnId 这张券对应的轮次；**只有这一轮的 {@code done} 才收流**
     * @return {@code seq > afterSeq} 的事件流：先补历史、再接实时（天然不重不漏）
     *
     * <p>收流条件必须绑轮次而不是「见到 done 就收」：回放缓冲里躺着**上一轮**的 done，
     * 按「见到 done 就收」会导致重连时刚补完历史就被上一轮的旧 done 掐断，永远看不到新一轮。
     * HITL 挂起时同样先收流：确认走 {@code POST /confirm} 换新券再续看（§19.9）。
     */
    public Flux<JournalRecord> streamAfter(long afterSeq, String turnId) {
        return sink.asFlux()
                .filter(record -> record.seq() > afterSeq)
                .takeUntil(record -> isDoneOf(record, turnId));
    }

    private static boolean isDoneOf(JournalRecord record, String turnId) {
        if (record.event().type() != com.djzy.assistant.common.sse.SseEventType.DONE) {
            return false;
        }
        Object doneTurn = record.event().payload().get("turnId");
        return doneTurn == null || turnId == null || turnId.equals(String.valueOf(doneTurn));
    }

    public long lastSeq() {
        return seq.get();
    }
}
