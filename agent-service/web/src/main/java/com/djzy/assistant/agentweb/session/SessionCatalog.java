package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentstate.PlatformSessionStore;
import com.djzy.assistant.agentstate.PlatformSessionState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话目录：建会话、查会话存不存在、列会话、读历史、归档（§19.4）。
 *
 * <p>**它是「读」的那一半**：所有内容都从框架的状态库现读现算（会话状态 + 平台会话档案），
 * 平台自己**不再维护任何一份会话台账**。这样「列表里有、点进去空的」这类不一致从源头上不存在。
 *
 * <p><b>两条读路径读的东西不一样，这是刻意的</b>：**历史回放**读对话正文
 * （{@code agent_state}，就是模型看见过的那串消息）；**会话列表**只读小档案
 * （{@code platform_session}：标题 / 条数 / 最后提问时刻 / 归档标记）。列表因此与「会话多不多」无关，
 * 一次正文都不读。
 *
 * <p>**为什么会话存不存在要查状态库而不是查内存**：请求会被负载均衡丢到任意一台实例上。
 * 只要「这个会话属于谁」看的是共享的状态库，换一台机器照样认得它（B 成立）；
 * 看内存就等于把会话钉在某一台上（这正是要根治的隐性单点）。
 */
public final class SessionCatalog {

    /**
     * 框架把「这轮对话的消息」存成哪个键。
     *
     * <p>**这是个耦合点，必须写清楚**：这个字符串是框架内部写死的（{@code ReActAgent} 里直接写
     * {@code "agent_state"}，没有对外常量）。我们靠它读历史，所以**升级 AgentScope 版本时要跑一次
     * 真实会话的冒烟**（{@code AgentStateContractTest}），把「框架改了键名 / 改了文档结构」
     * 变成一次红灯，而不是上线后历史突然全空。
     */
    public static final String AGENT_STATE_KEY = "agent_state";

    private static final Logger log = LoggerFactory.getLogger(SessionCatalog.class);

    private final AgentStateStore stateStore;
    private final PlatformSessionStore metaStore;
    private final PlatformLiveTurnStore liveTurnStore;

    public SessionCatalog(
            AgentStateStore stateStore, PlatformSessionStore metaStore, PlatformLiveTurnStore liveTurnStore) {
        this.stateStore = stateStore;
        this.metaStore = metaStore;
        this.liveTurnStore = liveTurnStore;
    }

    /** 建一个新会话：只生成会话号 + 落一条会话档案（可列表、可归档）；正文等用户提问时才产生。 */
    public String create(String userId) {
        String sessionId = UUID.randomUUID().toString();
        metaStore.create(userId, sessionId, System.currentTimeMillis());
        return sessionId;
    }

    /** 这个会话在这个用户名下存在吗（有档案或有过对话都算存在）。 */
    public boolean exists(String userId, String sessionId) {
        return sessionId != null && userId != null && stateStore.exists(userId, sessionId);
    }

    /** 历史会话：把会话状态投影成前端认的事件形状（不补「没跑完」的轮次时用这个）。 */
    public List<SessionTranscript.TurnSlice> history(String userId, String sessionId) {
        return history(userId, sessionId, null, SessionTranscript.Trailing.NONE);
    }

    /**
     * 历史会话，并补上「开始了但没跑完」的那一轮（T1-08 / T1-09）。
     *
     * <p>要不要补、补成什么样子，由调用方判断——它才拿得到跨副本的轮次闸门：
     * 标记还在但坑位空着 = 那台实例已经没了（中断）；标记还在且坑位占着 = 还在别的实例上跑（进行中）。
     */
    public List<SessionTranscript.TurnSlice> history(
            String userId,
            String sessionId,
            PlatformLiveTurnState live,
            SessionTranscript.Trailing trailing) {
        return SessionTranscript.turns(conversation(userId, sessionId), live, trailing);
    }

    /** 状态库里「当前这一轮」的开始标记（T1-08），没有就返回空。 */
    public java.util.Optional<PlatformLiveTurnState> liveTurn(String userId, String sessionId) {
        try {
            return liveTurnStore.load(userId, sessionId);
        } catch (RuntimeException e) {
            // 与读会话正文同一个口径：读不出来只记日志，不要让它把整页历史变成 500
            log.warn("读取未完成轮次标记失败，按「没有未完成轮次」处理：sessionId={}", sessionId, e);
            return java.util.Optional.empty();
        }
    }

    /** 我的会话列表（按最后提问时刻倒序）。归属过滤在状态库那一层：读的是 {@code user_id} 下的槽位。 */
    public List<SessionSummary> list(String userId) {
        List<SessionSummary> summaries = new ArrayList<>();
        for (String sessionId : metaStore.sessionIds(userId)) {
            summaries.add(SessionSummary.of(sessionId, archiveOf(userId, sessionId)));
        }
        summaries.sort(Comparator.comparingLong(SessionSummary::lastActiveMs)
                .reversed()
                .thenComparing(SessionSummary::sessionId));
        return List.copyOf(summaries);
    }

    /** 归档 / 取消归档，返回更新后的那一行。 */
    public SessionSummary archive(String userId, String sessionId, boolean archived) {
        PlatformSessionState meta = metaStore.setArchived(userId, sessionId, archived, System.currentTimeMillis());
        return SessionSummary.of(sessionId, meta);
    }

    /**
     * 记一次「这个会话又有人问了一句」（列表要的三个数）。
     *
     * <p>由提问入口调用，写在**跑模型之前**：列表要的是「用户问了什么」，不是「模型答完了没有」。
     */
    public void recordQuestion(String userId, String sessionId, String question, long nowMs) {
        metaStore.recordQuestion(userId, sessionId, question, nowMs);
    }

    /**
     * 取会话档案；没有就补一份再返回（升级前建的老会话只有对话、没有档案）。
     *
     * <p>补的是「建档时刻 = 现在、还没问过」的空档案：标题不会是编出来的，条数如实是 0，
     * 等用户再问一句就补齐。比「让它从列表里消失」和「回读整段对话去猜」都更直白。
     */
    private PlatformSessionState archiveOf(String userId, String sessionId) {
        return metaStore.load(userId, sessionId).orElseGet(() -> {
            metaStore.create(userId, sessionId, System.currentTimeMillis());
            return metaStore.load(userId, sessionId).orElse(null);
        });
    }

    /**
     * 读会话状态（消息本体）。
     *
     * <p>读不出来只记 warn 并当作「还没有对话」：一台实例的模型供应商没配好、或框架改了存储格式，
     * 都不该让用户连会话列表都打不开——那是把一个小故障放大成了整页不可用。
     */
    private AgentState conversation(String userId, String sessionId) {
        Optional<AgentState> state;
        try {
            state = stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        } catch (RuntimeException e) {
            log.warn("读取会话状态失败，按「还没有对话」处理：sessionId={}", sessionId, e);
            return null;
        }
        return state.orElse(null);
    }
}
