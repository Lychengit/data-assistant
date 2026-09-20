package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史会话回放（§19.4）：把回放位记录切成「一轮一轮」，前端用**同一个**事件归约器渲染。
 *
 * <p>为什么不在这里翻译成界面字段（question / answer / cards…）：那就等于把 §12.2 的事件表
 * 在服务端再实现一遍，而前端 `web/src/lib/turn.ts` 里的 `applyEvent` 已经是那一遍。
 * 两份映射一漂移，历史会话和实时流长的就不一样了——所以这里只做**分组**，
 * 事件本体原样下发，前端拿 `applyEvent` 走同一条渲染路径。
 *
 * <p>分组规则就是「看到用户提问就开新的一轮」：
 * <ul>
 *   <li>{@code user} 起一轮；
 *   <li>之后的所有事件都并入这一轮——**包括 HITL 确认续跑**（确认后是新 turnId，但界面上仍是同一条回答），
 *       以及运行时在 {@code done} 之后补发的事件；
 *   <li>{@code session} 是连接元数据、{@code archived} 是会话级状态，都不是对话内容，丢掉——
 *       归档不该在历史里凭空长出一轮空对话。
 * </ul>
 *
 * <p>**兼容说明**：{@code USER_MESSAGE} 事件是后加的，之前写下的会话里根本没有用户提问
 * （运行时也不会把正文放进 {@code TURN_START}）。这类会话会从第一条非 {@code session} 记录开始
 * 派生出一轮：答案还在，问题一栏只能空着。这是明示的取舍——把答案丢掉更糟。
 */
public final class SessionHistory {

    private SessionHistory() {}

    /**
     * @param turnId 由 {@code user} 事件带的轮次号；老会话拿不到时回退成 {@code turn-<seq>}
     *     （前端拿它当列表 key，必须唯一）
     * @param events 按 seq 升序的事件，形状与 SSE 一致（{@code {seq, name, data}}），
     *     因此前端可以直接喂给 {@code applyEvent}
     */
    public record TurnSlice(String turnId, List<StreamEventView> events) {}

    /** 一条事件的线上形态：与 SSE 的 {@code id:} / {@code event:} / {@code data:} 一一对应。 */
    public record StreamEventView(long seq, String name, Map<String, Object> data) {}

    public static List<TurnSlice> group(List<JournalRecord> records) {
        List<TurnSlice> turns = new ArrayList<>();
        List<StreamEventView> current = null;
        for (JournalRecord record : records) {
            SseEventType type = record.event().type();
            if (type == SseEventType.SESSION || type == SseEventType.ARCHIVED) {
                continue;
            }
            if (type == SseEventType.USER || current == null) {
                current = new ArrayList<>();
                turns.add(new TurnSlice(turnIdOf(record), current));
            }
            current.add(new StreamEventView(record.seq(), type.wireName(), record.event().payload()));
        }
        return List.copyOf(turns);
    }

    private static String turnIdOf(JournalRecord record) {
        Object turnId = record.event().payload().get("turnId");
        return turnId == null || String.valueOf(turnId).isBlank()
                ? "turn-" + record.seq()
                : String.valueOf(turnId);
    }
}