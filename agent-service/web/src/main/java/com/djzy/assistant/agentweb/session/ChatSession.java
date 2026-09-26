package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import com.djzy.assistant.spi.AgentSession;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 一次对话在**接入层**的内存状态：当前运行时句柄、这一轮跑到哪、以及最近一小段事件。
 *
 * <p>**它不再落任何盘**（这是本轮改造的关键简化）：会话正文的权威是框架的 {@code AgentState}
 * （在 PG 里，见 {@code agent-service/state}），历史会话由它投影出来。这里留的东西都是
 * 「只有正在跑的这一轮才需要、跑完就不必留」的：
 * <ul>
 *   <li>当前运行时句柄（用户点确认时得找到它，§19.9）；</li>
 *   <li>一轮之内 **seq 单调**的流序号：运行时自己的 seq 每轮从 0 开始，而 SSE 的
 *       {@code Last-Event-ID} 必须跨重连连续，否则刷新页面就会重放上一轮；</li>
 *   <li>最近若干条事件的**内存**回放位：让「刚断线又连上」不用等下一段产出。</li>
 * </ul>
 *
 * <p>为什么回放位可以只在内存里：它服务的是「这一轮还没跑完、用户刷新后**又连回了我这台**」这个场景——
 * 本实例自己产出的内容，顺手留一份不额外花代价。**跨到别的实例不吃这一份**：那边读的是共享总线
 * （{@link LiveTurnChannel}，H-01 / DR-34），两处都读不到时才退回「等本轮跑完整段补」（明示降级）。
 * 跑完的轮次不需要回放：它们已经在 {@code AgentState} 里，走历史接口即可。
 */
public final class ChatSession {

    private final String sessionId;
    private final String userId;
    private final Sinks.Many<StreamRecord> sink;
    private final AtomicLong seq = new AtomicLong(-1);
    /** 本实例见过哪些轮次：重连时用它判断「这一轮我到底有没有」，没有就明确收流而不是挂着。 */
    private final Set<String> knownTurns = new LinkedHashSet<>();

    private volatile boolean running;
    private volatile boolean suspended;
    private volatile String currentTurnId;
    private volatile AgentSession runtimeSession;
    /** 这一轮在本实例上占的坑位句柄（T1-07）：收尾时用它放坑；没抢到坑位时为 null。 */
    private volatile TurnLease turnLease;
    private volatile long lastTouchedMs = System.currentTimeMillis();

    ChatSession(String sessionId, String userId, int replayBufferSize) {
        this.sessionId = sessionId;
        this.userId = userId;
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

    public synchronized void beginTurn(String turnId, AgentSession runtimeSession) {
        this.currentTurnId = turnId;
        this.runtimeSession = runtimeSession;
        this.running = true;
        this.suspended = false;
        remember(turnId);
        touch();
    }

    public synchronized void endTurn() {
        this.running = false;
        touch();
    }

    /**
     * 结束这一轮，并回一句「这次是不是我结束的」。
     *
     * <p>谁该收尾是有竞争的：用户点停止、运行时自然跑完、流异常关闭，三件事可能几乎同时发生。
     * 让它们都来抢这一把锁，只有抢到的那一个会去记「这一轮结束了」——
     * 否则同一轮会出现两条结束记录、两条 {@code done}（§19.4 停止必须幂等）。
     */
    public synchronized boolean endTurnIfRunning() {
        if (!running) {
            return false;
        }
        running = false;
        touch();
        return true;
    }

    public synchronized void markSuspended() {
        this.suspended = true;
        this.running = false;
        touch();
    }

    public void clearRuntimeSession() {
        this.runtimeSession = null;
        touch();
    }

    /**
     * 记住「这一轮在本实例上占的坑位」，收尾时要用它放坑（T1-07）。
     *
     * <p>为什么挂在会话句柄上：坑位是「本实例正在跑这一轮」的证据，而会话句柄本来就是
     * 「本实例手上这一轮」的记录处（见 {@link #currentTurnId()} 与 {@link #runtimeSession()}）。
     * 记在别处就得再造一张「哪一轮归谁」的映射表，还得自己管它的过期与清理。
     */
    public synchronized void holdTurnLease(TurnLease lease) {
        this.turnLease = lease;
    }

    /** 取出并清空坑位句柄：收尾只放一次，重复调用返回 {@code null}。 */
    public synchronized TurnLease takeTurnLease() {
        TurnLease lease = this.turnLease;
        this.turnLease = null;
        return lease;
    }

    /** 记一条要下发给前端的事件（先分配 seq，再进回放缓冲）。 */
    public StreamRecord publish(String turnId, SseEvent event) {
        synchronized (this) {
            StreamRecord record = new StreamRecord(seq.incrementAndGet(), turnId, event, System.currentTimeMillis());
            remember(turnId);
            sink.tryEmitNext(record);
            touch();
            return record;
        }
    }

    /**
     * 订阅某一轮的事件流。
     *
     * @param afterSeq 客户端已经收到的最后一条 seq；**只补它之后那段**（不重不漏）
     * @param turnId 这张券对应的轮次；{@code null} 表示「不限轮次」（没有绑轮次的券才可能这样）
     *
     * <p>两条过滤各有各的用处，缺一条都会出问题：
     * <ul>
     *   <li>按 {@code seq} 过滤：同一轮内重连不重播，不然逐字流会把已经显示过的文字再吐一遍；</li>
     *   <li>按 {@code turnId} 过滤：换轮次后不重播**上一轮**的卡片（回放缓冲里躺着它们）。
     *       同时也让「游标来自别的实例 / 上一次进程」这种情况不至于把整轮回答丢掉——
     *       那种游标在本实例上没有意义，就退回「这一轮从头补」。</li>
     * </ul>
     *
     * <p>本实例压根没见过这一轮（请求落到了别的实例）时返回空流：连接立刻收掉，
     * 前端会如实提示「没看完」，而不是挂一条永远不说话的连接假装还在等（DR-09 的降级）。
     */
    public Flux<StreamRecord> streamTurn(long afterSeq, String turnId) {
        if (!knowsTurn(turnId)) {
            return Flux.empty();
        }
        long from = afterSeq > lastSeq() ? -1L : afterSeq;
        return sink.asFlux()
                .filter(record -> record.seq() > from)
                .filter(record -> turnId == null || turnId.equals(record.turnId()))
                .takeUntil(record -> isDoneOf(record, turnId));
    }

    public long lastSeq() {
        return seq.get();
    }

    /**
     * 这一轮本实例见过吗（正在跑、或刚跑完）。
     *
     * <p>接流时它决定走哪条路：见过就走本实例的实时 / 回放位；没见过说明这一轮在**别的实例**上
     * （或者早就没了），那本地没有可推的东西，得换别的办法（见 {@code CrossInstanceTurnRelay}）。
     */
    public boolean knowsTurn(String turnId) {
        synchronized (this) {
            return turnId == null || knownTurns.contains(turnId);
        }
    }

    /** 见过的轮次只留最近一小把：回放缓冲本来也是有界的，记多了只是白占内存。 */
    private void remember(String turnId) {
        if (turnId == null) {
            return;
        }
        knownTurns.add(turnId);
        while (knownTurns.size() > 8) {
            java.util.Iterator<String> first = knownTurns.iterator();
            first.next();
            first.remove();
        }
    }

    private static boolean isDoneOf(StreamRecord record, String turnId) {
        if (record.event().type() != SseEventType.DONE) {
            return false;
        }
        Object doneTurn = record.event().payload().get("turnId");
        return doneTurn == null || turnId == null || turnId.equals(String.valueOf(doneTurn));
    }
}
