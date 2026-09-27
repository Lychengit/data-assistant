package com.djzy.assistant.agentweb.service;

import com.djzy.assistant.agentweb.auth.EntryTicket;
import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import com.djzy.assistant.agentweb.prompt.SystemPromptComposer;
import com.djzy.assistant.agentweb.session.ChatSession;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.CrossInstanceTurnRelay;
import com.djzy.assistant.agentweb.session.LiveTurnChannel;
import com.djzy.assistant.agentweb.session.SessionCatalog;
import com.djzy.assistant.agentweb.session.SessionSummary;
import com.djzy.assistant.agentweb.session.SessionTranscript;
import com.djzy.assistant.agentweb.session.TurnGateKeys;
import com.djzy.assistant.agentweb.session.StreamRecord;
import com.djzy.assistant.agentweb.session.TurnStopChannel;
import com.djzy.assistant.agentweb.session.TurnStopSignalStore;
import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.agentweb.web.ApiException;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.bus.EventPublisher;
import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import com.djzy.assistant.common.web.auth.UserContextHolder;
import com.djzy.assistant.core.runtime.RuntimeRegistry;
import com.djzy.assistant.core.sse.SseProjector;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.PendingConfirmationException;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.Snapshot;
import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentstate.PlatformTurnStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 主对话流程（§18.4.1 / §19.4）：建会话 → 发起一轮 → 换券 → 订阅 SSE 流。
 *
 * <p>四条关键取舍：
 * <ul>
 *   <li>**轮次在后台跑，不绑定连接**：用户刷新页面 / 断网，答案照样生成完（§19.4）；
 *   <li>**会话正文只有一份权威**：框架的会话状态（在 PG 里）。这里不再自己记一份 SSE 台账——
 *       历史会话由状态投影而来（{@link SessionCatalog}），多副本下每台实例看到的自然是同一份；
 *       会话列表另走一条更轻的路：只读会话档案里的标题 / 条数，一次对话正文都不读（H-13）；
 *   <li>**每轮重取工具清单**：权限变更 ≤15 分钟生效，且骨架期零缓存直查 PG（§0.3-4）；
 *   <li>**先写日志再推进**：中性事件先追加 append-only 日志（审计的唯一事实源），再投影成 SSE 下发（§19.6）。
 * </ul>
 */
public final class ChatService {

    /** 归档事件的兜底轮次号：归档不属于任何一轮，没在聊的会话只能标成会话级（{@code turnId} 是必填字段）。 */
    private static final String SESSION_LEVEL_TURN = "session";

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatSessionRegistry registry;
    private final SessionCatalog catalog;
    private final EntryTicketService tickets;
    private final RuntimeRegistry runtimes;
    private final ToolPlane toolPlane;
    private final PlatformTurnStore turnStore;
    private final SseProjector projector;
    private final TurnLimiter rateLimiter;
    private final EventPublisher events;
    private final TurnStopSignalStore stopSignals;

    /**
     * 停止的**推送口**（H-09）：别的实例一点停止，本实例当场取消，不用等下一个流事件。
     *
     * <p>它和 {@link #stopSignals} 是一件事的两条路，都留着：推送负责快，信号键负责一定到得了
     * （推送丢了只会慢一点，不会停不下来）。细节见 {@link TurnStopChannel}。
     */
    private final TurnStopChannel stopChannel;

    private final SessionTurnGate turnGate;
    private final PlatformLiveTurnStore liveTurns;
    private final CrossInstanceTurnRelay remoteTurns;
    private final LiveTurnChannel liveChannel;
    private final AgentServiceProperties properties;

    /** 本实例的名字：写进轮次坑位与开始标记，排障时一眼能看出「这一轮是哪台机器在跑」。 */
    private final String instanceId;

    public ChatService(
            ChatSessionRegistry registry,
            SessionCatalog catalog,
            EntryTicketService tickets,
            RuntimeRegistry runtimes,
            ToolPlane toolPlane,
            PlatformTurnStore turnStore,
            SseProjector projector,
            TurnLimiter rateLimiter,
            EventPublisher events,
            TurnStopSignalStore stopSignals,
            TurnStopChannel stopChannel,
            SessionTurnGate turnGate,
            PlatformLiveTurnStore liveTurns,
            CrossInstanceTurnRelay remoteTurns,
            LiveTurnChannel liveChannel,
            String instanceId,
            AgentServiceProperties properties) {
        this.registry = registry;
        this.catalog = catalog;
        this.tickets = tickets;
        this.runtimes = runtimes;
        this.toolPlane = toolPlane;
        this.turnStore = turnStore;
        this.projector = projector;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.stopSignals = stopSignals;
        this.stopChannel = stopChannel;
        this.turnGate = turnGate;
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
        this.liveChannel = liveChannel;
        this.instanceId = instanceId;
        this.properties = properties;
        // 常驻订阅「有人喊停」这件事：注册一次，之后由推送线程回调。
        // 为什么放在构造里：一个进程只有一个 ChatService，订阅也只该有一份；
        // 装配好之后就没有第二个地方需要知道「收到推送该怎么办」了。
        stopChannel.subscribe(this::onPushedStop);
    }

    /** 我的会话列表（§19.4 历史会话）：现读状态库现算，不看任何一份本地台账。 */
    public List<SessionSummary> listSessions(String userId) {
        return catalog.list(userId);
    }

    /**
     * 某个会话的历史（§19.4 历史会话 / 切换会话接着聊）。
     *
     * <p>先验归属再读记录：别人的 sessionId 一律当作不存在，不泄露存在性（§11.3）。
     */
    public List<SessionTranscript.TurnSlice> history(String userId, String sessionId) {
        requireSession(userId, sessionId);
        // 状态库里还留着开始标记 → 有一轮开了头（跑完的轮次会把标记删掉）。
        PlatformLiveTurnState live = catalog.liveTurn(userId, sessionId).orElse(null);
        SessionTranscript.Trailing trailing = trailingOf(userId, sessionId, live);
        return catalog.history(userId, sessionId, live, trailing);
    }

    /**
     * 这一轮「开了头但没跑完」该显示成什么样子（T1-08 / T1-09）。
     *
     * <p>判断只用到一件事：**跨副本的轮次坑位有没有人占**。
     * <ul>
     *   <li>坑位有人占 → 还在别的实例上跑 → {@link SessionTranscript.Trailing#RUNNING}：
     *       问题先显示出来，前端会接着去接流等结果；</li>
     *   <li>坑位空着 → 那一轮**不会再有下文** → {@link SessionTranscript.Trailing#INTERRUPTED}：
     *       如实说「没跑完，请重发」，别让用户对着一个永远转圈的气泡干等。</li>
     * </ul>
     * 「有没有标记」和「坑位有没有人」两句必须一起看，只看一句就会把「正在生成」误报成「已经中断」。
     */
    private SessionTranscript.Trailing trailingOf(String userId, String sessionId, PlatformLiveTurnState live) {
        if (live == null) {
            return SessionTranscript.Trailing.NONE;
        }
        return turnGate.isRunning(TurnGateKeys.of(userId, sessionId))
                ? SessionTranscript.Trailing.RUNNING
                : SessionTranscript.Trailing.INTERRUPTED;
    }

    /**
     * 归档 / 取消归档（§19.4 会话列表）。
     *
     * <p>归档是**逻辑标记，不是删除**：规格把「归档与召回」列为后置能力，记录一律长期保留
     * （§8.3 / §20.5）。归档标记存在会话档案里（{@code platform_session}，与会话状态同一张表），
     * 所以它在多副本之间天然一致——在 A 台归档，到 B 台刷新列表照样是归档的。
     *
     * @return 归档后的摘要（含 {@code archived} / {@code archivedAtMs}），前端据此就地更新列表
     * @throws ApiException 404 会话不存在或不属于该用户（不泄露存在性，§11.3）
     */
    public SessionSummary archive(String userId, String sessionId, boolean archived) {
        requireSession(userId, sessionId);
        return catalog.archive(userId, sessionId, archived);
    }

    /** 建会话：只占一个会话号；正文等用户开口才产生（列表里它会显示成「未命名会话」）。 */
    public String createSession(String userId) {
        return catalog.create(userId);
    }

    /** 发起一轮：后台开跑并返回一次性入场券（令牌不进 URL，§19.4）。 */
    public EntryTicketService.IssuedTicket startTurn(
            String userId, String bearerToken, String sessionId, String text, List<String> attachments) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("消息不能为空");
        }
        ChatSession session = requireSession(userId, sessionId);
        if (session.running()) {
            throw ApiException.conflict("上一轮还在处理中，请等待完成或停止后再提问");
        }
        if (session.suspended()) {
            // 会话里还挂着一张没答复的写操作确认卡（§19.9）：这时再发新消息，框架会直接抛
            // 「有待批准的工具调用、这一轮却没给确认结果」，落到用户眼里就是「服务暂不可用，请稍后再试」
            // （2026-09-27 实测），而重试永远不会好。这里当场挡下来，并说清该做哪一步。
            // 只认内存里的挂起标记：进程重启过的那种由运行时侧的 PendingConfirmationException 兜底。
            throw ApiException.conflict(UnifiedErrors.CONFIRM_PENDING);
        }
        // 再问一句**跨副本**的：这一轮可能正跑在别的实例上，本实例的内存里看不到任何痕迹。
        // 真正把关的是下面 launch 里的占坑（这里是让人早点拿到 409，不必先发券再失败）。
        if (turnGate.isRunning(TurnGateKeys.of(userId, sessionId))) {
            throw ApiException.conflict("上一轮还在处理中，请等待完成或停止后再提问");
        }
        boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(userId);
        } catch (RedisTurnLimiter.TurnQuotaUnavailableException e) {
            // 计数存储不可用 = 算不出配额。不静默放行（那等于把 §2.3 的限额整条丢掉），
            // 也不退化成「本实例自己数」（那正是规格要根治的隐性单点），如实报服务不可用。
            log.error("轮次配额计数不可用，拒绝发起：userId={}", userId, e);
            throw ApiException.unavailable();
        }
        if (!allowed) {
            throw ApiException.rateLimited();
        }
        String turnId = UUID.randomUUID().toString();
        EntryTicketService.IssuedTicket issued = tickets.issue(userId, sessionId, turnId);
        launch(session, userId, bearerToken, turnId, text, attachments, false);
        return issued;
    }

    /**
     * 重连换券（§19.4）：**不发新轮次**，只再发一张绑同一会话的券，接着看当前/最近一轮。
     *
     * <p>券是一次性的，所以「刷新页面 / 断网重连接着看」不能靠复用旧券实现；
     * 浏览器原生的 {@code EventSource} 自动重连同样会拿着已核销的旧券去撞 401。
     * 于是这里补一个「只换券、不发起」的入口：客户端带上最后收到的 seq 重连，回放天然不重不漏。
     *
     * @throws ApiException 404 会话不存在或不属于该用户（不泄漏存在性）
     */
    public EntryTicketService.IssuedTicket resumeTicket(String userId, String sessionId) {
        ChatSession session = requireSession(userId, sessionId);
        String turnId = session.currentTurnId();
        if (turnId == null) {
            // 本实例没在跑这一轮（请求落到了别的实例）：状态库里的开始标记记着轮次号，
            // 带上它，接流那头才知道用户想接着看的是**哪一轮**——跨实例等待要靠它去认（T1-09）。
            // 哪台实例都没在跑（标记也没有）时留空串：券合法，但没什么可续看的。
            turnId = catalog.liveTurn(userId, sessionId)
                    .map(PlatformLiveTurnState::getTurnId)
                    .orElse("");
        }
        return tickets.issue(userId, sessionId, turnId);
    }

    /**
     * HITL 确认（§19.9）：消费确认结果并**续跑同一运行时会话**，返回续跑所需的券。
     *
     * <p>两种进入方式都要能用：
     * <ul>
     *   <li>进程还活着——运行时句柄就在会话里，直接确认；
     *   <li>**进程重启过 / 换过 pod**——句柄没了，从状态库里的挂起快照把运行时会话接回来再确认（§19.5）。
     *       接不回来（没有快照）就是 409：不能凭空造一个「已确认」，那等于绕过确认（§19.9 不可绕过）。
     * </ul>
     */
    public EntryTicketService.IssuedTicket confirm(
            String userId, String bearerToken, String sessionId, String confirmId, boolean approved) {
        ChatSession session = requireSession(userId, sessionId);
        String turnId = UUID.randomUUID().toString();
        AgentSession runtimeSession = session.runtimeSession();
        if (runtimeSession == null) {
            Snapshot snapshot = turnStore
                    .load(userId, sessionId)
                    .orElseThrow(() -> ApiException.conflict(UnifiedErrors.CONFIRM_NOT_UNIQUE));
            runtimeSession = runtimes.active()
                    .resume(
                            sessionId,
                            snapshot,
                            runRequest(session, userId, bearerToken, turnId, toolPlane.catalogFor(bearerToken)));
            session.beginTurn(turnId, runtimeSession);
        } else if (!session.suspended()) {
            throw ApiException.conflict(UnifiedErrors.CONFIRM_NOT_UNIQUE);
        }
        runtimes.active().confirm(runtimeSession, new ConfirmDecision(confirmId, approved, "", System.currentTimeMillis()));
        EntryTicketService.IssuedTicket issued = tickets.issue(userId, sessionId, turnId);
        launch(session, userId, bearerToken, turnId, "", List.of(), true);
        return issued;
    }

    /**
     * 停止当前这一轮（§19.4）。
     *
     * <p>三条语义缺一不可：
     * <ul>
     *   <li><b>只停这一轮</b>：会话还在，这一轮之前已经产出的内容原样保留，用户可以直接问下一轮；</li>
     *   <li><b>幂等</b>：重复点、或者这一轮早就跑完了再点，都只回一句结果，不报错、也不产生第二条停止记录；</li>
     *   <li><b>跨实例</b>：停止请求可能落到**不持有这一轮**的实例上，所以两头都做——信号写进共享存储
     *       （{@link TurnStopSignalStore}），同时往总线上推一条（{@link TurnStopChannel}，H-09）。
     *       真正在跑的那个实例收到推送就**当场**取消，不用等下一个流事件；推送没到（订阅断线、
     *       或者单实例用了内存总线）也照样停得掉，只是回到「下一个事件才停」的老延迟。
     *       本实例如果恰好就是持有者，则当场取消，不用等信号绕一圈。</li>
     * </ul>
     *
     * @return 停止结果（{@code sessionId} / {@code turnId} / {@code stopped}），前端据此更新按钮状态
     * @throws ApiException 404 会话不存在或不属于该用户（不泄露存在性，§11.3）
     */
    public Map<String, Object> stop(String userId, String sessionId) {
        ChatSession session = requireSession(userId, sessionId);
        String turnId = session.currentTurnId();
        if (turnId == null) {
            // 本实例没跑过这一轮：它可能跑在**别的实例**上。状态库里的开始标记记着轮次号，
            // 拿它去写停止信号，那台机器就能看到（这正是「停止要跨实例」的意义）。
            turnId = catalog.liveTurn(userId, sessionId)
                    .map(PlatformLiveTurnState::getTurnId)
                    .orElse(null);
        }
        if (turnId == null) {
            // 这个会话还没跑过任何一轮：没什么可停的，但也不算错（幂等）
            return Map.of("sessionId", sessionId, "turnId", "", "stopped", false);
        }
        stopSignals.request(sessionId, turnId);
        // 再推一条：让持有这一轮的那台机器**现在**就取消，而不是等它下一个流事件（H-09）。
        // 先写信号键再推送，是为了「推送这条路出问题也一定停得掉」这个兜底先落地；
        // 两条路互不依赖，命中同一轮也只取消一次（见 onPushedStop 的三问）。
        stopChannel.publish(userId, sessionId, turnId);
        cancelLocally(session, turnId, "user_stop");
        return Map.of("sessionId", sessionId, "turnId", turnId, "stopped", true);
    }

    /**
     * 用券换连接（§19.4 断线续传）：核销券，然后分两条路接流。
     *
     * <p><b>本实例见过这一轮</b>（正在跑、或刚跑完）→ 走原来的实时 / 回放位：用户接着看逐字输出。
     *
     * <p><b>本实例没见过</b>（请求被负载均衡丢到了别的实例）→ 本地根本没有这一轮的事件，
     * 推不出任何东西。这时交给 {@link CrossInstanceTurnRelay}：等别的实例把这一轮写进共享状态库，
     * 再整段交给用户（降级，但答得出来；DR-09 / T1-09）。
     */
    public Flux<StreamRecord> attach(String ticket, long afterSeq) {
        EntryTicket consumed = tickets.consume(ticket);
        ChatSession session = requireSession(consumed.userId(), consumed.sessionId());
        String turnId = consumed.turnId();
        if (session.knowsTurn(turnId)) {
            return session.streamTurn(afterSeq, turnId);
        }
        return watchFromOtherInstance(consumed.userId(), session, turnId);
    }

    /**
     * 接别的实例上那一轮的流（T1-09）。
     *
     * <p>能不能等，取决于状态库里还有没有这一轮的开始标记：标记在，就说明有一轮开了头且没人清掉它
     * （跑完的轮次会把标记删掉）。标记不在，或轮次号对不上，就是「本来就没有可续看的」——
     * 直接收流，不空挂一条不说话的连接。
     */
    private Flux<StreamRecord> watchFromOtherInstance(String userId, ChatSession session, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return Flux.empty();
        }
        PlatformLiveTurnState live = catalog.liveTurn(userId, session.sessionId()).orElse(null);
        if (live == null || !turnId.equals(live.getTurnId())) {
            return Flux.empty();
        }
        // seq 交给 ChatSession 统一分配（这里只决定「说什么」，不决定「第几条」）。
        //
        // 注意这里走的是 session.publish 而不是 emit：这些事件是**别人产出的**，我们只是转给用户看。
        // 顺手再往总线记一份会让同一段内容在同一轮的日志里出现两份，第三台实例看到的就是两遍。
        return remoteTurns.watch(userId, session.sessionId(), live)
                .map(event -> session.publish(turnId, event));
    }

    /**
     * 取这个会话在本实例的活口；会话**存不存在**由状态库说了算（多副本下换台机器照样认得它）。
     *
     * <p>为什么不用「内存里有才算」：那等于把会话钉在某一台实例上——用户在 A 台开的会话，
     * 请求飘到 B 台就变成 404，这正是这一轮改造要根治的病（B 不成立）。
     */
    private ChatSession requireSession(String userId, String sessionId) {
        if (!catalog.exists(userId, sessionId)) {
            throw ApiException.notFound();
        }
        return registry.getOrCreate(sessionId, userId);
    }

    private void launch(
            ChatSession session,
            String userId,
            String bearerToken,
            String turnId,
            String text,
            List<String> attachments,
            boolean continuation) {
        AgentRuntimePort runtime = runtimes.active();
        // 跨副本互斥（T1-07）：**先占坑，再干活**。抢不到就说明这个会话上已经有另一轮在跑
        // （可能在别的实例上），抢不到的那一轮当场收尾——发都发出去了，让人等一轮不会有结果的东西
        // 比直接说「上一轮还在处理中」更糟。
        TurnLease lease;
        try {
            lease = turnGate.acquire(TurnGateKeys.of(userId, session.sessionId()));
        } catch (TurnBusyException e) {
            abandonBecauseBusy(session, userId, turnId);
            return;
        } catch (InterruptedException e) {
            // 被中断说明这一轮已经没人要了（例如停机）。恢复中断标记后按「抢不到」收尾，
            // 不能让请求挂在这里，也不能把中断标记吞掉（吞掉会让上层误以为线程一切正常）。
            Thread.currentThread().interrupt();
            log.warn("等会话坑位时被中断，这一轮放弃：sessionId={} turnId={}", session.sessionId(), turnId);
            abandonBecauseBusy(session, userId, turnId);
            return;
        }
        session.holdTurnLease(lease);
        // 开始标记（T1-08）：开跑前先写下「这一轮开了头」，跑完再删。
        // 实例被硬杀时框架来不及把会话写进库（L-01），只剩这一个小标记能告诉用户
        // 「上一轮没跑完，请重发」——否则历史里会凭空少一轮。
        markTurnStarted(session, turnId, questionTextFor(session, userId, text, continuation));
        // 平台侧先记「用户问了什么」（§19.6 / ADR-28 ③ 模型可见即落库）：没有它，
        // conversation_turn.user_input 永远空，审计回放也重建不出这一轮问的是什么。
        // 续跑（HITL 确认）不算新提问，所以只在这一轮的首发路径上记。
        String traceId = UUID.randomUUID().toString();
        if (!continuation) {
            record(session, AgentEvent.builder(AgentEventType.USER_MESSAGE)
                    .session(session.sessionId())
                    .turn(turnId)
                    .trace(traceId)
                    .put("text", text)
                    .build());
            // 顺手更新会话列表要用的那三个数（标题 / 条数 / 最后提问时刻），见下面私有方法。
            recordQuestionForList(session, userId, text);
        }
        // 工具清单在这一轮取一次：既要挂给运行时，也要作为「本轮能力快照」贴到提问之后
        // （老会话的历史里存着模型自己上一轮的能力说法，它离提问更近，不贴就会被照着抄）。
        ToolCatalog tools = toolPlane.catalogFor(bearerToken);
        AgentTurn turn = new AgentTurn(
                turnId,
                traceId,
                text,
                attachments,
                Map.of(AgentTurn.ATTR_CONTEXT_REMINDER, SystemPromptComposer.turnReminder(tools)));
        Flux<AgentEvent> events;
        try {
            AgentSession runtimeSession = continuation && session.runtimeSession() != null
                    ? session.runtimeSession()
                    : runtime.start(runRequest(session, userId, bearerToken, turnId, tools));
            session.beginTurn(turnId, runtimeSession);
            events = Flux.from(runtime.stream(runtimeSession, turn));
        } catch (RuntimeMisconfiguredException e) {
            // 配置缺失是**可以照着改**的失败，不能和「服务故障」共用一句「请稍后再试」：
            // 用户唯一能做的动作是去管理端把模型供应商配上，所以把这句原样下发（§20.1.6：不含任何密钥）。
            log.error("轮次因运行时配置缺失而失败：sessionId={} turnId={}", session.sessionId(), turnId, e);
            failBeforeStream(session, turnId, SseEvent.of(
                    SseEventType.ERROR,
                    Map.of("code", AgentErrorCode.LLM_UNAVAILABLE.name(), "message", e.userMessage())));
            return;
        } catch (RuntimeException e) {
            log.error("发起轮次失败：sessionId={} turnId={}", session.sessionId(), turnId, e);
            failBeforeStream(session, turnId, SseEvent.of(
                    SseEventType.ERROR,
                    Map.of("code", AgentErrorCode.RUNTIME_UNAVAILABLE.name(), "message", UnifiedErrors.SERVICE_UNAVAILABLE)));
            return;
        }
        AtomicBoolean turnEnded = new AtomicBoolean(false);
        // 运行时的回调可能落在别的线程上（Reactor 的调度器 / SSE 推送线程），线程池不会自己继承
        // ThreadLocal。所以在**还在请求线程上**的时候先把身份包一层，回调里才拿得到正确的用户（§20.1.6-5）。
        events.subscribe(
                UserContextHolder.propagateConsumer(
                        event -> onEvent(session, turnId, event, turnEnded, text)),
                UserContextHolder.propagateConsumer(error -> {
                    log.error("轮次异常：sessionId={} turnId={}", session.sessionId(), turnId, error);
                    // 运行时会把两类**照做就能好**的失败翻译成自己的异常类型：配置缺失（去管理端配模型）、
                    // 会话里还挂着没答复的写操作确认卡（去点确认或取消）。剩下的才是真故障，统一收成一句
                    // 「服务暂不可用」（TCK-12：不外泄内部细节）。
                    String code;
                    String message;
                    if (error instanceof RuntimeMisconfiguredException misconfigured) {
                        code = AgentErrorCode.LLM_UNAVAILABLE.name();
                        message = misconfigured.userMessage();
                    } else if (error instanceof PendingConfirmationException pending) {
                        // 这类失败**不是**服务故障：用户少点了一次确认（§19.9）。说清那一步，
                        // 比让他对着「服务暂不可用」反复重试有用（2026-09-27 实测）。
                        code = AgentErrorCode.TOOL_CONFIRM_REQUIRED.name();
                        message = pending.userMessage();
                    } else {
                        code = AgentErrorCode.INTERNAL.name();
                        message = UnifiedErrors.SERVICE_UNAVAILABLE;
                    }
                    emit(session, turnId, SseEvent.of(
                            SseEventType.ERROR, Map.of("code", code, "message", message)));
                    finish(session, turnId, turnEnded);
                }),
                UserContextHolder.propagate(() -> finish(session, turnId, turnEnded)));
    }

    /**
     * 记一下「这个会话又有人问了一句」（H-13）：列表要的标题、提问条数、最后提问时刻都靠它。
     *
     * <p>**为什么写在跑模型之前**：列表要的是「用户问了什么」，不是「模型答完了没有」。等答完再写，
     * 用户问完立刻刷新列表会看不到这一条；这一轮要是跑挂了，会话反倒显得「没动静」，与事实相反。
     *
     * <p>**为什么失败只记日志**：它只是会话列表的一份索引，对话正文的真相始终在框架的会话状态里。
     * 索引没写上的代价是「列表少一行、或标题是旧的」，下一句提问就补回来了；为它把用户这一轮
     * 直接打回 500，是拿小事换大事。
     *
     * <p>**为什么不用管并发**：调用点在会话闸门（{@code turnGate.acquire}）之后，而闸门保证
     * 同一个会话同时只有一轮在跑——所以这里不会有两台实例同时改同一份档案。这也是它敢用
     * 「读出来改一改再写回去」这种简单写法的底气。
     */
    private void recordQuestionForList(ChatSession session, String userId, String text) {
        try {
            catalog.recordQuestion(userId, session.sessionId(), text, System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("记录会话列表用的提问索引失败（不影响本轮对话）：sessionId={}", session.sessionId(), e);
        }
    }

    private AgentRunRequest runRequest(
            ChatSession session, String userId, String bearerToken, String turnId, ToolCatalog tools) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId(session.sessionId())
                .requestId(turnId)
                .tools(tools)
                .visibleSkills(visibleSkills(bearerToken))
                .toolInvoker(toolPlane.invokerFor(bearerToken))
                .deadlineEpochMs(System.currentTimeMillis() + properties.getTurnDeadline().toMillis())
                .maxIters(properties.getMaxIters())
                // 系统提示词每轮现拼：相对时间必须取「这一轮」的值（§6.6），而且要把这一轮的接口清单一起写进去——
                // 权限被撤销后，工具面每轮刷新是对的，但老会话的历史里还留着「我能查名单」这类旧回答，
                // 模型回答「你有哪些能力」时会照着历史编；提示词里这份清单是它唯一该信的口径。
                .systemPromptPrefix(SystemPromptComposer.compose(Instant.now(), tools))
                .build();
    }

    /**
     * 这一轮可见的技能编码（H-06a）：技能内容由平台下发进用户工作区，运行时只管用（DR-41）。
     *
     * <p>只在技能下发**开着**的时候才去问网关：关着的时候（默认）问来的这份清单没人用，
     * 而能力清单是每轮都要调一次的接口——白多一次往返。
     */
    private List<String> visibleSkills(String bearerToken) {
        if (!properties.isWorkspaceSkillsEnabled()) {
            return List.of();
        }
        return toolPlane.skillsFor(bearerToken);
    }

    /**
     * 下发一条事件：**先在本地会话上记一份（分配 seq 进回放位），再往共享总线记一份**（H-01）。
     *
     * <p>为什么两件事收在同一个方法里：本实例的「刚才发过哪几条」与别的实例要看的「这一轮正在产出什么」，
     * 是同一条事实的两面。分开写就迟早有一处漏掉——漏了总线，别的实例只能等整轮跑完；
     * 漏了本地，当前这个页面自己反而收不到。
     *
     * <p>总线上那一份**只是加速**：写失败不影响这一轮，用户照样拿得到答案（见 LiveTurnChannel）。
     */
    private StreamRecord emit(ChatSession session, String turnId, SseEvent event) {
        StreamRecord record = session.publish(turnId, event);
        liveChannel.publishTurnEvent(session.userId(), session.sessionId(), turnId, event);
        return record;
    }

    /** 日志先行（§19.6）：中性事件先落 append-only 日志（审计的唯一事实源），再投影成 SSE。 */
    private void record(ChatSession session, AgentEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.error("会话日志写入失败（§19.6 必须告警）：sessionId={}", session.sessionId(), e);
            throw e;
        }
        projector.project(event).ifPresent(sse -> emit(session, event.turnId(), sse));
    }

    private void onEvent(ChatSession session, String turnId, AgentEvent event, AtomicBoolean turnEnded, String userText) {
        // 停止信号要在这里看一眼：停止请求可能落在别的实例上，只有共享信号能传过来。
        // 代价是每个流事件一次很轻的读取，换来的是「无论请求落到哪台机器都停得掉」。
        if (stopSignals.isRequested(session.sessionId(), turnId)) {
            stopSignals.clear(session.sessionId(), turnId);
            cancelLocally(session, turnId, "user_stop");
            // 这一轮已经收尾，后面再来的事件一律丢掉：说了停就不该继续产出内容
            return;
        }
        record(session, withUserText(event, userText));
        if (event.type() == AgentEventType.AWAITING_CONFIRM) {
            // 先记下来：框架拦下写调用后发的是 REQUEST_STOP，终点事件上不会带 suspended 标记。
            session.markConfirmRaised();
        }
        if (event.type() == AgentEventType.TURN_END || event.type() == AgentEventType.REQUEST_STOP) {
            turnEnded.set(true);
            // 「等确认」有两个来源：框架显式说等确认（TURN_END{suspended=true}），或**这一轮弹过确认卡**。
            // 后者是实测补上的：只认前者的话会话不会被标成挂起，用户点「确认」拿到 409（2026-09-27 实测）。
            if (suspended(event) || session.confirmRaised()) {
                session.markSuspended();
                saveSuspendSnapshot(session);
                // 挂起也是「这一轮跑完了」（等用户确认），坑位要放掉，否则用户点确认时会被自己挡住。
                // 但**开始标记要留着**：续跑那一轮会接着用它记住「用户问的是什么」。
                finishTurnBookkeeping(session, turnId, true);
            } else {
                clearSuspendSnapshot(session);
            }
        }
    }

    /**
     * 把这一轮的提问钉在终点事件上（§8.3 / ADR-28）。
     *
     * <p>为什么不能只靠消费端的「同批补齐」：事实表按 {@code event_id} 幂等，而消费端是**攒批**落库的，
     * 一条 {@code USER_MESSAGE} 和它那一轮的 {@code TURN_END} 完全可能落进两个批次
     * （一轮回答要跑好几秒，攒批间隔只有 100ms）。实测同一套代码，有的轮次 {@code user_input} 有值、
     * 有的为空——查询视图少了提问，回放与评估就重建不出这一轮问的是什么。
     * 提问跟终点一起走，落库就不再取决于批次边界。
     *
     * <p>续跑那一轮（{@code userText} 为空）不动它：确认不是新提问，不该凭空补一条用户输入。
     */
    private static AgentEvent withUserText(AgentEvent event, String userText) {
        if (userText == null || userText.isBlank() || event.type() != AgentEventType.TURN_END) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.putIfAbsent("userText", userText);
        return new AgentEvent(
                event.eventId(),
                event.type(),
                event.sessionId(),
                event.turnId(),
                event.traceId(),
                event.seq(),
                event.timestampEpochMs(),
                event.source(),
                payload);
    }

    /** 挂起有两种表达：明确的 {@code AWAITING_CONFIRM}，或 {@code TURN_END{suspended=true}}（都是正常终点，§19.9）。 */
    private static boolean suspended(AgentEvent event) {
        return event.type() == AgentEventType.AWAITING_CONFIRM
                || Boolean.TRUE.equals(event.payload().get("suspended"));
    }

    /**
     * 挂起时把运行时快照落 PG（§19.5）：一次落地同时解决「HITL 恢复 / 重启恢复 / 换 pod 恢复 / 断线续传」。
     *
     * <p>落库失败**不让这一轮炸掉**：用户已经拿到确认卡，进程内确认照样能走完；只是重启后接不回来，所以按 ERROR 告警（§10.2）。
     */
    private void saveSuspendSnapshot(ChatSession session) {
        AgentSession runtimeSession = session.runtimeSession();
        if (runtimeSession == null) {
            return;
        }
        try {
            Snapshot snapshot = runtimes.active().snapshot(runtimeSession);
            turnStore.save(session.userId(), session.sessionId(), snapshot);
        } catch (RuntimeException e) {
            log.error("挂起快照落库失败（重启 / 换 pod 后将无法续跑）：sessionId={}", session.sessionId(), e);
        }
    }

    /** 一轮正常跑完就清掉挂起位：留着会让「上一轮的确认」在新一轮里被误认成待确认（§19.9 确认必须一次性）。 */
    private void clearSuspendSnapshot(ChatSession session) {
        try {
            turnStore.clear(session.userId(), session.sessionId());
        } catch (RuntimeException e) {
            log.warn("清理挂起快照失败：sessionId={}", session.sessionId(), e);
        }
    }

    /**
     * 这一轮收尾（正常跑完 / 流异常关闭都走这里）。
     *
     * <p>先问一句「这一轮还在跑吗」再动手：用户点过停止时已经收过尾了，这里就不该再补一条
     * {@code done}（§19.4 停止必须幂等）。
     */
    private void finish(ChatSession session, String turnId, AtomicBoolean turnEnded) {
        if (!session.endTurnIfRunning()) {
            return;
        }
        releaseRuntime(session);
        finishTurnBookkeeping(session, turnId);
        // TURN_END 已经由投影器翻译成 done；这里只在运行时**没有**发 TURN_END 就结束流时补一个，
        // 保证前端不会挂着一个永远不闭合的连接（重复的 done 对前端是幂等的，但不能没有）。
        if (turnEnded.compareAndSet(false, true)) {
            emit(session, turnId, SseEvent.of(SseEventType.DONE, Map.of("turnId", turnId)));
        }
    }

    /**
     * 抢不到坑位：这一轮当场收尾（T1-07）。
     *
     * <p>为什么不是排队等：坑位租期按「整轮流超时」算，等它等于把用户的连接挂几分钟不响一声。
     * 直接说「上一轮还在处理中」，用户可以自己决定是等还是点停止，比默默排队好得多。
     *
     * <p>发 {@code TURN_IN_PROGRESS} 之后再补一个 {@code done}：这条流是「发出去的一张券」对应的流，
     * 有始必须有终，否则前端的连接会一直挂着等一条永远不来的结束事件。
     */
    private void abandonBecauseBusy(ChatSession session, String userId, String turnId) {
        log.warn(
                "没抢到会话坑位，这一轮放弃：sessionId={} turnId={} 持有者={}",
                session.sessionId(),
                turnId,
                holderOf(userId, session.sessionId()));
        emit(session, turnId, SseEvent.of(
                SseEventType.ERROR,
                Map.of("code", "TURN_IN_PROGRESS", "message", "上一轮还在处理中，请等待完成或停止后再提问")));
        emit(session, turnId, SseEvent.of(SseEventType.DONE, Map.of("turnId", turnId)));
    }

    /**
     * 排障用：现在是谁占着这个会话的坑位。
     *
     * <p>答案来自开始标记 {@code platform_turn_live}（记着轮次号与实例名），而不是闸门本身——
     * 闸门只回答「有没有人」，坑位里存的是随机令牌，回答不了「是谁」。与其在闸门里再存一份，
     * 不如用平台已有的那份事实（它连用户的原话都记着）。读不到就如实说「未知」：
     * 这是日志里的辅助信息，不值得因为它失败而影响这一轮的收尾。
     */
    private String holderOf(String userId, String sessionId) {
        try {
            return liveTurns
                    .load(userId, sessionId)
                    .map(live -> live.getTurnId() + '@' + live.getInstanceId())
                    .orElse("未知");
        } catch (RuntimeException e) {
            return "未知";
        }
    }

    /**
     * 写「这一轮开了头」的标记（T1-08）。
     *
     * <p>失败**不让这一轮起不来**：标记是「事后解释用」的，代价是「这一轮如果真挂了，历史里少一条解释」，
     * 而把用户挡在门外是更大的损失。所以只记一条 warn。
     */
    private void markTurnStarted(ChatSession session, String turnId, String questionText) {
        try {
            liveTurns.start(
                    session.userId(),
                    session.sessionId(),
                    new PlatformLiveTurnState(turnId, instanceId, questionText, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("写轮次开始标记失败（实例挂了时历史里会少一条中断提示）：sessionId={}", session.sessionId(), e);
        }
    }

    /**
     * 这一轮要记进标记的「用户问了什么」。
     *
     * <p>续跑（HITL 确认）那一轮没有新提问，就把**上一轮留下来**的原文接着用——
     * 挂起时我们刻意没删开始标记，正是为了这个：万一续跑也挂了，用户还能看到自己当初问的是什么。
     */
    private String questionTextFor(ChatSession session, String userId, String text, boolean continuation) {
        if (!continuation) {
            return text;
        }
        try {
            return liveTurns.load(userId, session.sessionId())
                    .map(PlatformLiveTurnState::getText)
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("读上一轮提问失败（续跑的中断提示会少一句原话）：sessionId={}", session.sessionId(), e);
            return "";
        }
    }

    /**
     * 一轮在**还没开始推流**的时候就失败了：把界面、坑位、开始标记一并收掉。
     *
     * <p>为什么单独一个方法：这一段有三件事必须一起做（推 error、推 done、放坑删标记），
     * 分两处写迟早会漏一件——漏了坑位就是「这个会话卡住 15 分钟」，漏了标记就是「历史里多一条假中断」。
     */
    private void failBeforeStream(ChatSession session, String turnId, SseEvent error) {
        emit(session, turnId, error);
        emit(session, turnId, SseEvent.of(SseEventType.DONE, Map.of("turnId", turnId)));
        session.endTurn();
        finishTurnBookkeeping(session, turnId);
    }

    /** 一轮真的结束了（跑完 / 停掉 / 失败）：放坑 + 删开始标记。 */
    private void finishTurnBookkeeping(ChatSession session, String turnId) {
        finishTurnBookkeeping(session, turnId, false);
    }

    /**
     * 收尾里的两件跨副本的事。
     *
     * @param suspended {@code true} = 这一轮挂起等用户确认：坑位要放（用户马上要续跑），
     *     但开始标记**留着**（续跑要用它拿回用户的原话）
     */
    private void finishTurnBookkeeping(ChatSession session, String turnId, boolean suspended) {
        TurnLease lease = session.takeTurnLease();
        if (lease != null) {
            try {
                lease.close();
            } catch (RuntimeException e) {
                // 放不掉也不致命：坑位带 TTL，到点自己会消失。但必须留痕，否则「卡 15 分钟」会变成幽灵问题。
                log.warn("释放轮次坑位失败（TTL 到点会自己过期）：sessionId={} turnId={}", session.sessionId(), turnId, e);
            }
        }
        if (suspended) {
            return;
        }
        try {
            liveTurns.clear(session.userId(), session.sessionId());
        } catch (RuntimeException e) {
            log.warn("清理轮次开始标记失败（下一轮会覆盖它）：sessionId={}", session.sessionId(), e);
        }
    }

    /**
     * 一轮结束后告诉运行时「这个会话的本地缓存可以丢了」（T1-06 / DR-22）。
     *
     * <p>为什么必须做：共享 agent 会按 {@code (userId, sessionId)} 缓存会话状态与权限引擎，
     * 这些表没有上限；不清就等于「用过这台机器的会话数」决定内存占用。清掉不会丢数据——
     * 配了状态库时框架每次调用开始时都会重新加载（这正是换实例能接上同一会话的原因）。
     *
     * <p>为什么失败不抛：这是收尾里的清理动作。已经跑完的一轮不该因为清理失败而变成错误，
     * 但也不能静默——所以记一条 warn 让人能查到。
     */
    private void releaseRuntime(ChatSession session) {
        AgentSession runtimeSession = session.runtimeSession();
        if (runtimeSession == null) {
            return;
        }
        try {
            runtimes.active().release(runtimeSession);
        } catch (RuntimeException e) {
            log.warn("释放运行时会话缓存失败（不影响本轮结果）：sessionId={}", session.sessionId(), e);
        }
    }

    /**
     * 收到别的实例推来的「请停掉这一轮」（H-09）。
     *
     * <p>推送是广播，每个实例都收得到，所以这里先问三件事，任何一件不成立就什么都不做：
     * <ol>
     *   <li>本实例有没有这个会话的活口（{@link ChatSessionRegistry}）；</li>
     *   <li>这一轮**正在跑**——不跑的轮次收到推送无事可做，这一条也顺手覆盖了「停止和跑完撞在一起」；</li>
     *   <li>本实例跑的正是被喊停的那一轮（用户可能已经在聊下一轮了）。</li>
     * </ol>
     * 三问都过了才取消。取消本身是幂等的（{@link ChatSession#endTurnIfRunning()}），所以
     * 「推送」与「本实例直接取消」同时命中也不会取消两遍、不会记两条停止事件。
     *
     * <p>这个方法跑在**推送线程**上（Redis 订阅线程），不是原来那个 HTTP 线程，所以里面只做
     * 「改标志 + 收尾 + 记一条事件」这类很快的事：它占着订阅线程，堵久了会拖慢别的实例的停止推送。
     */
    private void onPushedStop(String userId, String sessionId, String turnId) {
        ChatSession session = registry.find(sessionId, userId);
        if (session == null || !session.running() || !turnId.equals(session.currentTurnId())) {
            // 不是本实例在跑这一轮（或者已经停了、已经翻页了）：静默忽略。
            // 刻意不记日志——推送是广播，绝大多数实例都属于「不是我在跑」，记下来全是噪音。
            return;
        }
        log.info("收到别的实例推来的停止（H-09）：sessionId={} turnId={}", sessionId, turnId);
        cancelLocally(session, turnId, "user_stop");
    }

    /**
     * 把这一轮就地取消并收尾。
     *
     * <p>运行时会话句柄只在本实例上才有；拿不到（真正在跑的是别台机器）就只做收尾——
     * 那边会通过共享信号自己停下来。
     */
    private void cancelLocally(ChatSession session, String turnId, String reason) {
        // 先占坑再动手：把 running 从 true 改成 false 的那一次调用，才是真正负责收尾的那一次。
        // 竞态（用户重复点停止 / 停止与跑完撞在一起）全靠这一句裁决，重复调用在这里直接返回。
        if (!session.endTurnIfRunning()) {
            return;
        }
        AgentSession runtimeSession = session.runtimeSession();
        if (runtimeSession != null) {
            try {
                runtimes.active().cancel(runtimeSession, reason);
            } catch (RuntimeException e) {
                // 取消失败也照样收尾：用户已经说了「停」，界面必须停下来，不能让这一轮一直挂着
                log.warn("取消运行时失败，仍按已停止收尾：sessionId={} turnId={}", session.sessionId(), turnId, e);
            }
        }
        releaseRuntime(session);
        finishTurnBookkeeping(session, turnId);
        // 往审计事实源记一条「用户叫停」，它会被投影成 done 发给前端。
        // 为什么不另外加一个「已取消」状态字段：状态是从事实源派生的（ADR-28），
        // 多一个字段就多一个真相；事件记进去了，回放、列表、跨实例读到的都是同一份。
        record(session, AgentEvent.builder(AgentEventType.REQUEST_STOP)
                .session(session.sessionId())
                .turn(turnId)
                .trace(UUID.randomUUID().toString())
                .put("reason", reason)
                .build());
        // 被停掉的轮次不是「待确认」：挂起位留着会挡住下一轮（§19.9 确认必须一次性）
        clearSuspendSnapshot(session);
    }
}
