package com.djzy.assistant.agentweb.service;

import com.djzy.assistant.agentweb.auth.EntryTicket;
import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.config.AgentServiceProperties;
import com.djzy.assistant.agentweb.prompt.SystemPromptComposer;
import com.djzy.assistant.agentweb.session.ChatSession;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.JournalRecord;
import com.djzy.assistant.agentweb.session.SessionHistory;
import com.djzy.assistant.agentweb.session.SessionSummary;
import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.agentweb.web.ApiException;
import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.bus.EventPublisher;
import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import com.djzy.assistant.core.runtime.RuntimeRegistry;
import com.djzy.assistant.core.sse.SseProjector;
import com.djzy.assistant.spi.AgentErrorCode;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.RuntimeStatePort;
import com.djzy.assistant.spi.Snapshot;
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
 * <p>三个关键取舍：
 * <ul>
 *   <li>**轮次在后台跑，不绑定连接**：用户刷新页面 / 断网，答案照样生成完，重连从回放位接着看（§19.4）；
 *   <li>**每轮重取工具清单**：权限变更 ≤15 分钟生效，且骨架期零缓存直查 PG（§0.3-4）；
 *   <li>**先写日志再推进**：中性事件先追加 append-only 日志（唯一事实源），再投影成 SSE 下发（§19.6）。
 * </ul>
 */
public final class ChatService {

    /** 挂起快照的 key（§19.5）：一个会话同时最多一段待确认，固定 key 就够，不必再给 key 加维度。 */
    private static final String SUSPEND_KEY = "turn";

    /** 归档事件的兜底轮次号：归档不属于任何一轮，没在聊的会话只能标成会话级（{@code turnId} 是必填字段）。 */
    private static final String SESSION_LEVEL_TURN = "session";

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatSessionRegistry registry;
    private final EntryTicketService tickets;
    private final RuntimeRegistry runtimes;
    private final ToolPlane toolPlane;
    private final RuntimeStatePort statePort;
    private final SseProjector projector;
    private final TurnLimiter rateLimiter;
    private final EventPublisher events;
    private final AgentServiceProperties properties;

    public ChatService(
            ChatSessionRegistry registry,
            EntryTicketService tickets,
            RuntimeRegistry runtimes,
            ToolPlane toolPlane,
            RuntimeStatePort statePort,
            SseProjector projector,
            TurnLimiter rateLimiter,
            EventPublisher events,
            AgentServiceProperties properties) {
        this.registry = registry;
        this.tickets = tickets;
        this.runtimes = runtimes;
        this.toolPlane = toolPlane;
        this.statePort = statePort;
        this.projector = projector;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.properties = properties;
    }

    /** 我的会话列表（§19.4 历史会话）：归属过滤在日志层做，本方法拿不到别人的会话。 */
    public List<SessionSummary> listSessions(String userId) {
        return registry.list(userId);
    }

    /**
     * 某个会话的历史回放（§19.4 历史会话 / 切换会话接着聊）。
     *
     * <p>先验归属再读记录：直接读记录也能靠日志层过滤兜住，但**显式的 404**才是规格要的形状
     * （别人的 sessionId 一律当作不存在，不泄露存在性，§11.3），而且顺带把会话在内存里重建出来，
     * 用户接着提问时无需再走一次恢复。
     */
    public List<SessionHistory.TurnSlice> history(String userId, String sessionId) {
        requireSession(userId, sessionId);
        return SessionHistory.group(registry.records(userId, sessionId));
    }

    /**
     * 归档 / 取消归档（§19.4 会话列表）。
     *
     * <p>归档是**逻辑标记，不是删除**：规格把「归档与召回」列为后置能力，骨架期的记录一律长期保留
     * （§8.3 / §20.5）。所以这里只往唯一事实源追加一条归档事件，会话与它的每一轮回答都原样还在——
     * 取消归档就能接着聊，也没有任何一份数据因为「归档」两个字被抹掉。
     *
     * <p>为什么追加事件而不是去改一张「会话目录表」里的字段：事实源只有这一条顺序日志（ADR-28）。
     * 写进日志，重启、回放、跨实例读到的都是同一份；另开一张表就等于给会话状态安了第二个真相，
     * 迟早说不清哪个先坏（列表本来就是从回放位派生的，见 {@link SessionSummary}）。
     *
     * <p>写失败**如实报错**，不吞：报成功了却只在本实例内存里记着，重启就变回未归档，
     * 用户会以为归档没生效（或缺配置时静默退化成「没归档」）——那比直接报错更难查。
     *
     * @return 归档后的摘要（含 {@code archived} / {@code archivedAtMs}），前端据此就地更新列表
     * @throws ApiException 404 会话不存在或不属于该用户（不泄露存在性，§11.3）
     */
    public SessionSummary archive(String userId, String sessionId, boolean archived) {
        ChatSession session = requireSession(userId, sessionId);
        record(
                session,
                AgentEvent.builder(AgentEventType.SESSION_ARCHIVED)
                        .session(session.sessionId())
                        // 有当前轮次就挂上去，日志里这一条才对得上上下文；没在聊就是会话级
                        .turn(session.currentTurnId() == null ? SESSION_LEVEL_TURN : session.currentTurnId())
                        .trace(UUID.randomUUID().toString())
                        .put("archived", archived)
                        .build());
        return SessionSummary.from(sessionId, registry.records(userId, sessionId));
    }

    public String createSession(String userId) {
        ChatSession session = registry.create(userId);
        session.publish(SseEvent.of(SseEventType.SESSION, Map.of("sessionId", session.sessionId())));
        return session.sessionId();
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
        return tickets.issue(userId, sessionId, session.currentTurnId());
    }

    /**
     * HITL 确认（§19.9）：消费确认结果并**续跑同一运行时会话**，返回续跑所需的券。
     *
     * <p>两种进入方式都要能用：
     * <ul>
     *   <li>进程还活着——运行时句柄就在会话里，直接确认；
     *   <li>**进程重启过 / 换过 pod**——句柄没了，从 PG 里的挂起快照把运行时会话接回来再确认（§19.5）。
     *       接不回来（没有快照）就是 409：不能凭空造一个「已确认」，那等于绕过确认（§19.9 不可绕过）。
     * </ul>
     */
    public EntryTicketService.IssuedTicket confirm(
            String userId, String bearerToken, String sessionId, String confirmId, boolean approved) {
        ChatSession session = requireSession(userId, sessionId);
        String turnId = UUID.randomUUID().toString();
        AgentSession runtimeSession = session.runtimeSession();
        if (runtimeSession == null) {
            Snapshot snapshot = statePort
                    .load(userId, sessionId, SUSPEND_KEY)
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

    /** 用券换连接：核销券并从 {@code afterSeq} 之后继续（断线续传，§19.4）。 */
    public Flux<JournalRecord> attach(String ticket, long afterSeq) {
        EntryTicket consumed = tickets.consume(ticket);
        ChatSession session = registry.getOrRestore(consumed.sessionId(), consumed.userId());
        if (session == null) {
            throw ApiException.notFound();
        }
        return session.streamAfter(afterSeq, consumed.turnId());
    }

    private ChatSession requireSession(String userId, String sessionId) {
        // 用 getOrRestore 而不是 find：进程重启后内存里没有会话，回放位里有——不断线续传、HITL 续跑都要靠它（§19.4 / §19.5）。
        ChatSession session = registry.getOrRestore(sessionId, userId);
        if (session == null) {
            throw ApiException.notFound();
        }
        return session;
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
        // 平台侧先记「用户问了什么」（§19.6 / ADR-28 ③ 模型可见即落库）：没有它，
        // conversation_turn.user_input 永远空、历史会话没有标题，而且这段正文确实进了模型请求，
        // 日志却重建不出来。续跑（HITL 确认）不算新提问，所以只在这一轮的首发路径上记。
        String traceId = UUID.randomUUID().toString();
        if (!continuation) {
            record(session, AgentEvent.builder(AgentEventType.USER_MESSAGE)
                    .session(session.sessionId())
                    .turn(turnId)
                    .trace(traceId)
                    .put("text", text)
                    .build());
        }
        // 工具清单在这一轮取一次：既要挂给运行时，也要作为「本轮能力快照」贴到提问之后
        // （老会话的历史里存着模型自己上一轮的能力说法，它离提问更近，不贴就会被照着抄）。
        ToolCatalog catalog = toolPlane.catalogFor(bearerToken);
        AgentTurn turn = new AgentTurn(
                turnId,
                traceId,
                text,
                attachments,
                Map.of(AgentTurn.ATTR_CONTEXT_REMINDER, SystemPromptComposer.turnReminder(catalog)));
        Flux<AgentEvent> events;
        try {
            AgentSession runtimeSession = continuation && session.runtimeSession() != null
                    ? session.runtimeSession()
                    : runtime.start(runRequest(session, userId, bearerToken, turnId, catalog));
            session.beginTurn(turnId, runtimeSession);
            events = Flux.from(runtime.stream(runtimeSession, turn));
        } catch (RuntimeMisconfiguredException e) {
            // 配置缺失是**可以照着改**的失败，不能和「服务故障」共用一句「请稍后再试」：
            // 用户唯一能做的动作是去管理端把模型供应商配上，所以把这句原样下发（§20.1.6：不含任何密钥）。
            log.error("轮次因运行时配置缺失而失败：sessionId={} turnId={}", session.sessionId(), turnId, e);
            session.publish(SseEvent.of(
                    SseEventType.ERROR,
                    Map.of("code", AgentErrorCode.LLM_UNAVAILABLE.name(), "message", e.userMessage())));
            session.publish(SseEvent.of(SseEventType.DONE, Map.of("turnId", turnId)));
            session.endTurn();
            return;
        } catch (RuntimeException e) {
            log.error("发起轮次失败：sessionId={} turnId={}", session.sessionId(), turnId, e);
            session.publish(SseEvent.of(
                    SseEventType.ERROR,
                    Map.of("code", AgentErrorCode.RUNTIME_UNAVAILABLE.name(), "message", UnifiedErrors.SERVICE_UNAVAILABLE)));
            session.publish(SseEvent.of(SseEventType.DONE, Map.of("turnId", turnId)));
            session.endTurn();
            return;
        }
        AtomicBoolean turnEnded = new AtomicBoolean(false);
        events.subscribe(
                event -> onEvent(session, turnId, event, turnEnded, text),
                error -> {
                    log.error("轮次异常：sessionId={} turnId={}", session.sessionId(), turnId, error);
                    // 运行时已经把「能照着改」的配置类失败翻译成了 RuntimeMisconfiguredException；
                    // 剩下的才是真故障，统一收成一句「服务暂不可用」（TCK-12：不外泄内部细节）。
                    boolean misconfigured = error instanceof RuntimeMisconfiguredException;
                    session.publish(SseEvent.of(
                            SseEventType.ERROR,
                            Map.of(
                                    "code",
                                    misconfigured ? AgentErrorCode.LLM_UNAVAILABLE.name() : AgentErrorCode.INTERNAL.name(),
                                    "message",
                                    misconfigured
                                            ? ((RuntimeMisconfiguredException) error).userMessage()
                                            : UnifiedErrors.SERVICE_UNAVAILABLE)));
                    finish(session, turnId, turnEnded);
                },
                () -> finish(session, turnId, turnEnded));
    }

    private AgentRunRequest runRequest(
            ChatSession session, String userId, String bearerToken, String turnId, ToolCatalog catalog) {
        return AgentRunRequest.builder()
                .userId(userId)
                .sessionId(session.sessionId())
                .requestId(turnId)
                .tools(catalog)
                .toolInvoker(toolPlane.invokerFor(bearerToken))
                .statePort(statePort)
                .deadlineEpochMs(System.currentTimeMillis() + properties.getTurnDeadline().toMillis())
                .maxIters(properties.getMaxIters())
                // 系统提示词每轮现拼：相对时间必须取「这一轮」的值（§6.6），而且要把这一轮的接口清单一起写进去——
                // 权限被撤销后，工具面每轮刷新是对的，但老会话的历史里还留着「我能查名单」这类旧回答，
                // 模型回答「你有哪些能力」时会照着历史编；提示词里这份清单是它唯一该信的口径。
                .systemPromptPrefix(SystemPromptComposer.compose(Instant.now(), catalog))
                .build();
    }

    /** 日志先行（§19.6）：中性事件先落 append-only 日志，再投影成 SSE。 */
    private void record(ChatSession session, AgentEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.error("会话日志写入失败（§19.6 必须告警）：sessionId={}", session.sessionId(), e);
            throw e;
        }
        projector.project(event).ifPresent(session::publish);
    }

    private void onEvent(ChatSession session, String turnId, AgentEvent event, AtomicBoolean turnEnded, String userText) {
        record(session, withUserText(event, userText));
        if (event.type() == AgentEventType.TURN_END) {
            turnEnded.set(true);
            if (suspended(event)) {
                session.markSuspended();
                saveSuspendSnapshot(session);
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
            statePort.save(session.userId(), session.sessionId(), SUSPEND_KEY, snapshot);
        } catch (RuntimeException e) {
            log.error("挂起快照落库失败（重启 / 换 pod 后将无法续跑）：sessionId={}", session.sessionId(), e);
        }
    }

    /** 一轮正常跑完就清掉挂起位：留着会让「上一轮的确认」在新一轮里被误认成待确认（§19.9 确认必须一次性）。 */
    private void clearSuspendSnapshot(ChatSession session) {
        try {
            statePort.delete(session.userId(), session.sessionId(), SUSPEND_KEY);
        } catch (RuntimeException e) {
            log.warn("清理挂起快照失败：sessionId={}", session.sessionId(), e);
        }
    }

    private void finish(ChatSession session, String turnId, AtomicBoolean turnEnded) {
        session.endTurn();
        // TURN_END 已经由投影器翻译成 done；这里只在运行时**没有**发 TURN_END 就结束流时补一个，
        // 保证前端不会挂着一个永远不闭合的连接（重复的 done 对前端是幂等的，但不能没有）。
        if (turnEnded.compareAndSet(false, true)) {
            session.publish(SseEvent.of(SseEventType.DONE, Map.of("turnId", turnId)));
        }
    }
}
