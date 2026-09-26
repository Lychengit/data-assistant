package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 「这一轮不在我手上」时的接流办法（T1-09 / DR-09 / H-01）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>用户在 A 实例上问了一句，网络抖了一下；重连的请求被负载均衡丢到了 B 实例。
 * A 实例手上那份「这一轮的事件回放位」只在 A 的内存里（那是刻意的，见 {@link ChatSession}），
 * 所以 B 实例**没有任何东西可以推**：不处理的话，连接要么一直挂着不说话，
 * 要么立刻断开，用户看到的是「连接已断开」——而他那一轮其实正在 A 上好好跑着。
 *
 * <h2>它怎么做：两条腿走路，一条快一条稳</h2>
 *
 * <p><b>快的那条</b>：A 每产出一条要下发的事件，就往共享总线记一条（见 {@link LiveTurnChannel}）。
 * B 按游标追着读，于是回答**边跑边到**——这就是 H-01 说的「跨副本实时续看」。
 *
 * <p><b>稳的那条</b>：光靠总线不够——总线可能没接（单实例的 {@code memory} 实现）、
 * 也可能那一轮根本是**没有实时通路的实例**在跑（老版本、或总线上那一段已经过期）。
 * 所以这里照旧每分钟看几次共享的会话状态（{@link SessionCatalog} 的投影），必要时把整轮的
 * 投影原样补过来。**实时是加速，不是前提**：总线断掉时，用户得到的还是改造前那份「整段补」。
 *
 * <h2>三种结局都必须有交代，绝不能空挂</h2>
 *
 * <ul>
 *   <li><b>跑完了</b>：整轮的事件（含末尾的 {@code done}）原样发出去；</li>
 *   <li><b>已经没人管了</b>（实例硬挂）：发「这一轮没跑完，请重发」（与历史里的口径同一句）；</li>
 *   <li><b>等到窗口结束还在跑</b>：如实说「还在处理中，可以稍后再连」，并闭合这一轮。</li>
 * </ul>
 *
 * <p><b>为什么一个轮询循环里同时做「追实时」和「看状态」，而不是两个流合起来</b>：
 * 两处都会产出「同一条内容」的可能（实时读到一半、状态那边整轮补上），合流就要去重与排序。
 * 收到一个循环里、共用一个游标，只有在**实时一条都没读到**的时候才走整段补，
 * 于是「不重不漏」是靠结构保证的，不是靠事后去重。
 *
 * <p>等待是有窗口的（{@code waitBudget}），不会把连接和线程无限占住。
 */
public final class CrossInstanceTurnRelay {

    /** 等到窗口结束还在跑：不是失败，是「还没等到」。前端据此提示用户稍后再连。 */
    public static final String TURN_STILL_RUNNING_CODE = "TURN_STILL_RUNNING";

    private static final Logger log = LoggerFactory.getLogger(CrossInstanceTurnRelay.class);

    private final SessionCatalog catalog;
    private final SessionTurnGate turnGate;

    /** 共享总线上的「这一轮正在产出什么」（H-01）；单实例下是内存实现，读不到东西而已，不会出错。 */
    private final LiveTurnChannel liveChannel;

    /** 多久追一次实时（H-01 的实时粒度：一个间隔一批，用户看到的就是「边跑边到」）。 */
    private final Duration livePollInterval;

    /** 多久看一眼共享的会话状态。它比追实时贵得多（要读一遍这一轮的投影），而且那三种结局
     * 本来就不需要毫秒级粒度，所以单独一个更慢的节奏，不跟着实时那条走。 */
    private final Duration stateCheckInterval;

    /** 一次连接最多等多久（要小于 SSE 本身的超时，否则前端只会看到一个「莫名其妙断了的连接」）。 */
    private final Duration waitBudget;

    /** 轮次坑位的存活时长：判断「没人管了」时要拿它做参照（见 {@link #abandoned}）。 */
    private final Duration lease;

    public CrossInstanceTurnRelay(
            SessionCatalog catalog,
            SessionTurnGate turnGate,
            LiveTurnChannel liveChannel,
            Duration livePollInterval,
            Duration stateCheckInterval,
            Duration waitBudget,
            Duration lease) {
        this.catalog = catalog;
        this.turnGate = turnGate;
        this.liveChannel = liveChannel;
        this.livePollInterval = positiveOr(livePollInterval, Duration.ofMillis(200));
        this.stateCheckInterval = positiveOr(stateCheckInterval, Duration.ofSeconds(1));
        this.waitBudget = waitBudget;
        this.lease = lease;
    }

    /** 配成 0 或负数是没有意义的（那是一个死循环），一律退回一个能跑的默认值。 */
    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    /**
     * 等这一轮结束，并把它的内容作为事件流交给调用方。
     *
     * <p>调用方（{@link com.djzy.assistant.agentweb.service.ChatService#attach}）负责把这些事件
     * 交给 {@link ChatSession#publish} 换成带 seq 的线上记录——seq 由会话统一分配，
     * 这里不去碰它，免得两处各自编号。
     *
     * @param live 这一轮的开始标记（有它才知道轮次号、用户问的原话、以及从什么时候开始等的）
     */
    public Flux<SseEvent> watch(String userId, String sessionId, PlatformLiveTurnState live) {
        // 记下「接流这一刻已经跑完了几轮」：之后一旦多一轮，就是这一轮写进去了。
        // 注意只数**跑完**的轮次：历史里可能已经挂着这一轮（进行中，没有 done），
        // 拿总轮数当基线就会永远等不到「多出来一轮」（见 SessionTranscript.Trailing）。
        int baseline = completedTurns(catalog.history(userId, sessionId));
        // 先把用户当初问的那句话发出去：前端这一轮的气泡要有问题，否则只剩一段没头没尾的回答
        List<SseEvent> head = question(live);
        Waiting waiting = new Waiting();
        // 探测次数 = 等待窗口 / 轮询间隔。用「有限次数的探测」而不是无限定时器，
        // 是为了让等待有一个**看得见的尽头**：到点一定收尾，不会留下一条不说话的连接。
        int maxPolls = (int) Math.max(1, waitBudget.toMillis() / livePollInterval.toMillis());
        // 有没有下过 done（可能是别的实例实时带过来的，也可能是我们整段补的）：
        // 下过就不再补「还在处理中」那一句——这一轮已经闭合了。
        AtomicBoolean closed = new AtomicBoolean(false);
        return Flux.concat(
                Flux.fromIterable(head),
                Flux.range(0, maxPolls)
                        // concatMap：一次只探一下（探测之间隔一个轮询间隔），既不用并发探测，也不会有背压问题
                        .concatMap(ignored -> Mono.delay(livePollInterval)
                                .then(Mono.fromCallable(() -> probe(userId, sessionId, live, baseline, !head.isEmpty(), waiting))
                                        // 读总线是阻塞动作，放到弹性线程池上，不要占住 Reactor 的事件线程
                                        .subscribeOn(Schedulers.boundedElastic())))
                        .flatMapIterable(events -> events)
                        .doOnNext(event -> {
                            if (event.type() == SseEventType.DONE) {
                                closed.set(true);
                            }
                        })
                        // 拿到 done 就收工：实时那条路可能比状态库先送到这一轮的结尾
                        .takeUntil(event -> event.type() == SseEventType.DONE)
                        // 等到窗口结束都没结论：如实收尾，别把用户晾在一条永远不闭合的连接上
                        .concatWith(Flux.defer(() -> closed.get() ? Flux.empty() : Flux.fromIterable(stillRunning(live)))));
    }

    /**
     * 看一眼这一轮现在什么样，返回「这一轮该下发的新事件」（可能为空，空就是「还没到时候」）。
     *
     * <p>**先看实时、再看共享状态**，顺序不能反：反了的话，状态库那边一看到「跑完」就整段补，
     * 而实时那条路还在往上下发——同一段文字会下发两遍。
     */
    private List<SseEvent> probe(
            String userId,
            String sessionId,
            PlatformLiveTurnState live,
            int baseline,
            boolean questionAlreadySent,
            Waiting waiting) {
        List<SseEvent> fresh = drainLiveEvents(userId, sessionId, live, waiting);
        if (!fresh.isEmpty()) {
            return fresh;
        }
        // 实时那条路已经接上了：这一轮的结尾会由对方的 done 带过来，不必再整段补
        // （整段补会把已经下发过的文字再下发一遍）。这里只兜一件事：跑这一轮的人已经不在了。
        if (waiting.liveDelivered) {
            return abandoned(userId, sessionId, live) ? interrupted(live) : List.of();
        }
        // 状态库那条路更贵，按更慢的节奏看（见 stateCheckInterval 的说明）：
        // 到点之前先什么都不做，接着追实时。
        long now = System.currentTimeMillis();
        if (now < waiting.nextStateCheckAtMs) {
            return List.of();
        }
        waiting.nextStateCheckAtMs = now + stateCheckInterval.toMillis();
        List<SessionTranscript.TurnSlice> turns;
        try {
            turns = catalog.history(userId, sessionId);
        } catch (RuntimeException e) {
            // 读不出来（库抖了一下 / 框架改了存储格式）不算结论：继续等，窗口结束会给出交代
            log.warn("等待别的实例跑完时读会话状态失败，继续等待：sessionId={}", sessionId, e);
            return List.of();
        }
        if (completedTurns(turns) > baseline) {
            return replay(turns.get(turns.size() - 1), questionAlreadySent);
        }
        if (abandoned(userId, sessionId, live)) {
            return interrupted(live);
        }
        return List.of();
    }

    /**
     * 追一次实时：把共享总线上「游标之后」的新事件读回来。
     *
     * <p>读失败只记一条日志、返回空：这条链路是**加速**，不是前提。真正的三道交代（跑完 / 没人管 / 超时）
     * 由状态库那条路负责，不会因为总线抖一下就失去结论。
     */
    private List<SseEvent> drainLiveEvents(
            String userId, String sessionId, PlatformLiveTurnState live, Waiting waiting) {
        try {
            LiveTurnChannel.Chunk chunk =
                    liveChannel.readSince(userId, sessionId, live.getTurnId(), waiting.cursor);
            waiting.cursor = chunk.cursor();
            if (!chunk.events().isEmpty()) {
                waiting.liveDelivered = true;
            }
            return chunk.events();
        } catch (RuntimeException e) {
            log.warn("读共享总线失败，改等状态库那条路：sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 这一轮的等待状态。
     *
     * <p>只被上面那个**串行**的轮询循环使用（{@code concatMap} 保证了同一时刻只有一次探测），
     * 所以这里是普通字段、不加锁：加锁只会让人以为它能被并发用，而它并不能。
     */
    private static final class Waiting {
        /** 总线上的读游标（条目号）；空串 = 从头读。 */
        private String cursor = "";

        /** 有没有从总线上读到过内容——它决定「能不能整段补」（读过就不能，否则同一段会下发两遍）。 */
        private boolean liveDelivered;

        /** 下一次可以读共享会话状态的时刻（毫秒）：状态那条路比追实时慢，见 stateCheckInterval。 */
        private long nextStateCheckAtMs;
    }

    /**
     * 数一数有几轮是**真跑完了**的。
     *
     * <p>判据是投影里有没有 {@code done}：跑完的轮次末尾一定有它，正在跑的那一轮没有
     * （见 {@link SessionTranscript.Trailing#RUNNING}）。用「轮次总数」是不行的——
     * 历史里可能已经挂着这一轮，总数不变，等待就永远结束不了。
     */
    private static int completedTurns(List<SessionTranscript.TurnSlice> turns) {
        int completed = 0;
        for (SessionTranscript.TurnSlice turn : turns) {
            for (SessionTranscript.StreamEventView view : turn.events()) {
                if (SseEventType.DONE.wireName().equals(view.name())) {
                    completed++;
                    break;
                }
            }
        }
        return completed;
    }

    /**
     * 这一轮是不是**已经没人管了**。
     *
     * <p>两个条件缺一不可，少一个都会误判：
     * <ul>
     *   <li><b>坑位空着</b>：还在跑的话坑位一定有人占（哪怕跑在别的实例上）；</li>
     *   <li><b>开始标记已经是「很久以前」</b>：坑位带 TTL，一轮跑得比 TTL 还久时它也会变空，
     *       只看坑位会把「跑得慢」错判成「挂了」。所以再拿标记的年龄和租期比一比。</li>
     * </ul>
     */
    private boolean abandoned(String userId, String sessionId, PlatformLiveTurnState live) {
        if (turnGate.isRunning(TurnGateKeys.of(userId, sessionId))) {
            return false;
        }
        return System.currentTimeMillis() - live.getStartedAtMs() > lease.toMillis();
    }

    /**
     * 这一轮跑完了：把投影出来的事件（末尾自带 {@code done}）原样交给用户。
     *
     * @param questionAlreadySent 等待期间已经发过「用户问的那句话」：投影的第一条也是它，
     *     再发一遍就是同一句话推两次（前端不会错，但流里多一条没有意义的记录，排障时会误导人）
     */
    private static List<SseEvent> replay(SessionTranscript.TurnSlice turn, boolean questionAlreadySent) {
        List<SseEvent> events = new ArrayList<>(turn.events().size());
        boolean leading = questionAlreadySent;
        for (SessionTranscript.StreamEventView view : turn.events()) {
            if (leading && SseEventType.USER.wireName().equals(view.name())) {
                leading = false;
                continue;
            }
            leading = false;
            events.add(SseEvent.of(SseEventType.fromWireName(view.name()), view.data()));
        }
        return events;
    }

    /**
     * 这一轮已经没人管了：与历史接口说同一句话，免得同一个故障在两边有两种说法。
     *
     * <p>不再重复「用户问的那句话」——它已经在 {@link #watch} 开头发过了（head）。
     */
    private static List<SseEvent> interrupted(PlatformLiveTurnState live) {
        List<SseEvent> events = new ArrayList<>();
        events.add(SseEvent.of(
                SseEventType.ERROR,
                Map.of(
                        "code", SessionTranscript.TURN_INTERRUPTED_CODE,
                        "message", SessionTranscript.TURN_INTERRUPTED_MESSAGE)));
        events.add(SseEvent.of(SseEventType.DONE, Map.of("turnId", live.getTurnId())));
        return events;
    }

    /**
     * 等到窗口结束还在跑：把这一轮**闭合**掉（发 done），并说清楚为什么现在看不到结果。
     *
     * <p>同样不再重复用户的原话（见 {@link #interrupted}）。
     */
    private static List<SseEvent> stillRunning(PlatformLiveTurnState live) {
        List<SseEvent> events = new ArrayList<>();
        events.add(SseEvent.of(
                SseEventType.ERROR,
                Map.of(
                        "code", TURN_STILL_RUNNING_CODE,
                        "message", "这一轮还在别的实例上处理中，等待已超时；问题不会丢，稍后可以再点「重新连接」查看结果。")));
        events.add(SseEvent.of(SseEventType.DONE, Map.of("turnId", live.getTurnId())));
        return events;
    }

    /** 「用户当初问了什么」。原话为空（例如确认续跑）时干脆不发——前端保留它自己的占位问题。 */
    private static List<SseEvent> question(PlatformLiveTurnState live) {
        String text = live.getText() == null ? "" : live.getText();
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(SseEvent.of(SseEventType.USER, Map.of("text", text, "turnId", live.getTurnId())));
    }
}
