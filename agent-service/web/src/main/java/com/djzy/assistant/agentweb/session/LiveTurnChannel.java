package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「这一轮正在产出什么」的跨副本共享口（H-01）。
 *
 * <h2>它解决什么</h2>
 *
 * <p>一轮对话在 A 实例上跑着，用户网络抖了一下，重连被负载均衡丢到 B 实例。
 * B 手上没有任何这一轮的事件（回放位是本实例内存里的东西，刻意的，见 {@link ChatSession}），
 * 于是改造前只能「等 A 跑完、再把整段补过来」——用户对着一个不动的气泡等完整轮回答。
 *
 * <p>现在 A 每产出一条就要下发的事件，就顺手往**共享总线**上记一条；B 按游标追着读，
 * 于是回答是**边跑边到**的（每个轮询间隔一批），而不是最后一次性出现。
 *
 * <h2>为什么用「追日志」而不是「订阅推送」</h2>
 *
 * <p>框架的 {@link MessageBus} 两种都支持（{@code sessionReadEvents} 读日志、
 * {@code sessionSubscribeEvents} 订阅推送）。这里选前者，理由是**顺序与不重不漏**：
 * <ul>
 *   <li>日志天然有序（条目号单调递增），读到哪就是哪，不需要「先订阅还是先补历史」这种取舍；</li>
 *   <li>推送那条路在断线重连的交界处必然要么丢一段、要么重一段，得再写一层去重与排序；</li>
 *   <li>模型本身是「一个字一个字吐」的，一个轮询间隔一批读回来，用户感知不到差别。</li>
 * </ul>
 *
 * <p>要换成真推送时，只需要改这一个类（总线那边的 {@code subscribe} 已经实现好了）。
 *
 * <h2>生命周期</h2>
 *
 * <p>这里的日志是**临时数据**，不是会话存档：会话正文的权威始终是 PG 里的框架状态（§19.5）。
 * 所以 Redis 实现给它配了 TTL，内存实现按条目数封顶（框架每轮给的上限是 1000 条），
 * 都不需要额外的清理任务。
 */
public final class LiveTurnChannel {

    private static final Logger log = LoggerFactory.getLogger(LiveTurnChannel.class);

    /** 信封字段：轮次号（收端据此丢掉别的轮次的事件）。 */
    private static final String FIELD_TURN_ID = "turnId";
    /** 信封字段：事件名（SSE 的 {@code event:} 那一行，用线名而不是枚举名，免得改枚举顺序就串了）。 */
    private static final String FIELD_EVENT = "event";
    /** 信封字段：事件的载荷（与线上 SSE 的 {@code data:} 完全同一份，收端不用二次加工）。 */
    private static final String FIELD_DATA = "data";

    private final MessageBus bus;
    private final int readLimit;

    public LiveTurnChannel(MessageBus bus, int readLimit) {
        this.bus = bus;
        this.readLimit = readLimit;
    }

    /**
     * 记一条「这一轮刚产出了这个事件」。
     *
     * <p>**失败不让这一轮炸掉**：这条记录只是「别的实例能不能实时看到」的问题，
     * 丢了它，跨副本续看会退回「等跑完整段补」的老办法（{@code CrossInstanceTurnRelay} 的兜底），
     * 用户仍然拿得到答案。把用户已经等到的回答判成失败，才是真的糟糕。
     */
    public void publishTurnEvent(String userId, String sessionId, String turnId, SseEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(FIELD_TURN_ID, turnId);
        envelope.put(FIELD_EVENT, event.type().wireName());
        envelope.put(FIELD_DATA, event.payload());
        try {
            // 总线实现是同步的（见 RedisMessageBus 的类注释），所以 subscribe 一调用就写完了，
            // 顺序与调用顺序一致——这正是「逐字流」最要紧的性质。
            bus.sessionPublishEvent(slot(userId, sessionId), envelope).subscribe(ignored -> {}, error -> {
                log.warn("记一条实时事件失败（跨副本续看会退回整段补）：sessionId={} turnId={}", sessionId, turnId, error);
            });
        } catch (RuntimeException e) {
            log.warn("记一条实时事件失败（跨副本续看会退回整段补）：sessionId={} turnId={}", sessionId, turnId, e);
        }
    }

    /**
     * 从游标之后读一批「这一轮」的事件（同步读；调用方负责放到弹性线程池上，别占住事件线程）。
     *
     * @param cursor 上一次读到的条目号；空串表示从头读
     * @return 这一批新事件 + **新游标**。游标一定要用返回的这个：日志里也可能夹着同一会话里
     *     别的轮次的条目，那些虽然不发给用户，也已经被读掉了，游标必须跟着往前走，否则会反复重读。
     */
    public Chunk readSince(String userId, String sessionId, String turnId, String cursor) {
        List<BusEntry> entries = bus.sessionReadEvents(slot(userId, sessionId), cursor, readLimit)
                .block();
        if (entries == null || entries.isEmpty()) {
            return new Chunk(List.of(), cursor);
        }
        List<SseEvent> events = new ArrayList<>();
        String next = cursor;
        for (BusEntry entry : entries) {
            next = entry.entryId();
            SseEvent event = toEvent(entry.payload(), turnId);
            if (event != null) {
                events.add(event);
            }
        }
        return new Chunk(events, next);
    }

    /** 一批读回来的东西：要下发的事件，以及下次该从哪儿接着读。 */
    public record Chunk(List<SseEvent> events, String cursor) {}

    /** 把总线上的信封还原成 SSE 事件；不是这一轮的、或者事件名认不出来的，返回 {@code null}（跳过）。 */
    @SuppressWarnings("unchecked")
    private static SseEvent toEvent(Map<String, Object> envelope, String turnId) {
        if (!String.valueOf(turnId).equals(String.valueOf(envelope.get(FIELD_TURN_ID)))) {
            return null;
        }
        Object name = envelope.get(FIELD_EVENT);
        if (name == null) {
            return null;
        }
        Object data = envelope.get(FIELD_DATA);
        Map<String, Object> payload = data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        try {
            return SseEvent.of(SseEventType.fromWireName(String.valueOf(name)), payload);
        } catch (IllegalArgumentException e) {
            // 认不出来的事件名只跳过这一条：为了它把整条续看链路断掉，代价远大于收益。
            log.warn("总线上有一个认不出来的事件名，已跳过：{}", name);
            return null;
        }
    }

    /**
     * 一个会话在总线上的槽位号。
     *
     * <p>与轮次闸门共用同一套拼法（{@link TurnGateKeys}）：两者都是「这个会话」的键，
     * 拼法一致，跨实例才会认成同一行；各拼一次迟早会改歪一边。
     */
    private static String slot(String userId, String sessionId) {
        return TurnGateKeys.of(userId, sessionId);
    }
}
