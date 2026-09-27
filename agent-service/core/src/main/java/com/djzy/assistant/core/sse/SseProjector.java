package com.djzy.assistant.core.sse;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 中立事件 → SSE 业务投影（§12.2 / ADR-27 ②）。
 *
 * <p>框架专有事件类型不得直接进入 SSE；投影只认中立 {@link AgentEvent}，
 * 因此换运行时不动前端。思考流是否下发由用户设置决定（§20.6）。
 */
public final class SseProjector {

    private static final Logger log = LoggerFactory.getLogger(SseProjector.class);

    /** 一段旁白里至少有多少个拉丁字母，才值得怀疑它是「英文旁白」。 */
    private static final int NON_CHINESE_MIN_LATIN = 12;

    /** 至少几个「两字母以上的词」，才算像一句英文（挡住 {@code perf.xlsx} 这类文件名）。 */
    private static final int NON_CHINESE_MIN_WORDS = 3;

    private final Supplier<ThinkingVisibility> thinkingVisibility;

    public SseProjector(Supplier<ThinkingVisibility> thinkingVisibility) {
        this.thinkingVisibility = thinkingVisibility == null ? () -> ThinkingVisibility.STEP_CARD_ONLY : thinkingVisibility;
    }

    public Optional<SseEvent> project(AgentEvent event) {
        Map<String, Object> p = event.payload();
        return switch (event.type()) {
            case TURN_START -> Optional.empty();
            // 用户提问不是模型事件，但同样要进回放位：切换历史会话时靠它把「问了什么」还原出来。
            case USER_MESSAGE -> Optional.of(SseEvent.of(
                    SseEventType.USER,
                    Map.of("text", str(p, "text", ""), "turnId", event.turnId())));
            // 归档是会话级状态：前端拿它刷新列表，不参与某个轮次的气泡
            case SESSION_ARCHIVED -> Optional.of(SseEvent.of(
                    SseEventType.ARCHIVED, Map.of("archived", Boolean.TRUE.equals(p.get("archived")))));
            case THOUGHT_DELTA, THOUGHT -> thinkingEvent(event);
            case TEXT_DELTA -> Optional.of(SseEvent.of(SseEventType.TOKEN, Map.of("delta", str(p, "delta", "text"))));
            case TEXT -> step("message", event);
            case TOOL_CALL_START -> Optional.of(SseEvent.of(
                    SseEventType.TOOL,
                    Map.of(
                            "toolName", str(p, "toolName", ""),
                            "args", p.getOrDefault("arguments", Map.of()),
                            "phase", "start")));
            case TOOL_CALL_ARGS_DELTA -> Optional.empty();
            case TOOL_CALL_END -> Optional.empty();
            // 带上入参（§12.2 tool 事件形状：toolName / args / resultSize）：
            // 参数在 START 事件发出去时还没吐完，只能到结果这一站一起给前端。
            case TOOL_RESULT -> Optional.of(SseEvent.of(
                    SseEventType.TOOL,
                    Map.of(
                            "toolName", str(p, "toolName", ""),
                            "args", str(p, "arguments", ""),
                            "resultSize", p.getOrDefault("resultSize", 0),
                            "status", str(p, "status", "ok"),
                            "phase", "result")));
            case AWAITING_CONFIRM -> Optional.of(SseEvent.of(
                    SseEventType.CONFIRM,
                    Map.of(
                            "confirmId", str(p, "confirmId", ""),
                            "action", str(p, "action", ""),
                            "summary", str(p, "summary", ""))));
            case CONFIRM_RESULT -> Optional.of(SseEvent.of(
                    SseEventType.CONFIRM,
                    Map.of(
                            "confirmId", str(p, "confirmId", ""),
                            "approved", p.getOrDefault("approved", false),
                            "phase", "result")));
            case AWAITING_EXTERNAL_EXECUTION -> custom(event, SseEventType.SKILL_STEP);
            case EXTERNAL_EXECUTION_RESULT -> custom(event, SseEventType.SKILL_STEP);
            case SUBAGENT_START, SUBAGENT_END -> Optional.of(SseEvent.of(
                    SseEventType.SKILL_STEP,
                    Map.of("source", event.source(), "phase", event.type().name())));
            case ALL_TOOLS_DENIED -> Optional.of(SseEvent.of(
                    SseEventType.PERMISSION, Map.of("allowed", false, "denied", true)));
            case EXCEED_MAX_ITERS -> Optional.of(SseEvent.of(
                    SseEventType.ERROR, Map.of("code", "MAX_ITERS_EXCEEDED", "message", "已达到最大推理步数")));
            // 用户叫停（REQUEST_STOP）：这一条是「停止」在事件流里的**唯一出口**，
            // 收尾事件带上 stopped=true，前端据此把这一轮定稿成「已停止生成」。
            // 为什么不让前端自己猜：停止接口的响应和这条事件谁先到是不确定的，
            // 由服务端在事件里说清楚，两种先后顺序下界面的结论都一样。
            case REQUEST_STOP -> Optional.of(SseEvent.of(
                    SseEventType.DONE, Map.of("turnId", event.turnId(), "stopped", Boolean.TRUE)));
            // 一轮正常跑完、或者挂起等确认：普通收尾，不额外标东西。
            case TURN_END -> Optional.of(SseEvent.of(SseEventType.DONE, Map.of("turnId", event.turnId())));
            case ERROR -> Optional.of(SseEvent.of(
                    SseEventType.ERROR,
                    Map.of(
                            "code", str(p, "code", "INTERNAL"),
                            "message", str(p, "userMessage", "服务暂不可用，请稍后再试"))));
            case CUSTOM -> custom(event, SseEventType.STEP);
        };
    }

    private Optional<SseEvent> thinkingEvent(AgentEvent event) {
        if (thinkingVisibility.get() != ThinkingVisibility.RAW_REASONING) {
            return step("thought", event);
        }
        return Optional.of(SseEvent.of(
                SseEventType.STEP, Map.of("type", "thought", "seq", event.seq(), "content", str(event.payload(), "delta", ""))));
    }

    private static Optional<SseEvent> step(String type, AgentEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("seq", event.seq());
        payload.put("source", event.source());
        String content = str(event.payload(), "delta", str(event.payload(), "text", ""));
        payload.put("content", content);
        if (event.payload().get("toolName") != null) {
            payload.put("toolName", event.payload().get("toolName"));
        }
        if (isNonChineseNarration(content)) {
            // 语言要求写在系统提示词里，但**拦不住**模型（2026-09-27 实测：中文结论外面套着
            // 「I'll load the export skill…」这种英文旁白）。这里不改写内容——改写模型的话是另一种
            // 撒谎方式——而是把它标出来并留痕：日志里能立刻看出还剩多少违规，界面也能据此处理。
            log.warn("模型输出了非中文旁白（应当全程中文）：seq={} source={} 片段={}", event.seq(), event.source(), preview(content));
            payload.put("lang", "en");
        }
        return Optional.of(SseEvent.of(SseEventType.STEP, payload));
    }

    /**
     * 判断一段旁白是不是「非中文」：**一个中日韩字符都没有、又确实像一句英文**才算。
     *
     * <p>为什么口径要这么保守：工具名、路径、字段名、代码片段本来就该是英文，一句中文里夹几个
     * 英文词也完全正常。所以三件事同时成立才算：没有汉字、含路径分隔符以外的拉丁字母不少于
     * {@link #NON_CHINESE_MIN_LATIN} 个、且至少 {@link #NON_CHINESE_MIN_WORDS} 个「两字母以上的词」。
     * 带 {@code /} 或 {@code \} 的一律放行——那是路径 / 命令，不是旁白。
     *
     * <p>判错的代价是单向的：漏报只是少一条日志，误报会在界面上给正常内容贴上「非中文」标签。
     * 所以宁可漏，不可误报。
     */
    static boolean isNonChineseNarration(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.indexOf('/') >= 0 || text.indexOf('\\') >= 0) {
            return false;
        }
        int latin = 0;
        int words = 0;
        int run = 0;
        for (int index = 0; index <= text.length(); index++) {
            char ch = index == text.length() ? ' ' : text.charAt(index);
            if (isCjk(ch)) {
                return false;
            }
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                latin++;
                run++;
                continue;
            }
            if (run >= 2) {
                words++;
            }
            run = 0;
        }
        return latin >= NON_CHINESE_MIN_LATIN && words >= NON_CHINESE_MIN_WORDS;
    }

    /** 中日韩统一表意文字（含扩展 A）与中文标点里最具辨识度的那几个。 */
    private static boolean isCjk(char ch) {
        return (ch >= '\u4e00' && ch <= '\u9fff') || (ch >= '\u3400' && ch <= '\u4dbf');
    }

    private static String preview(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 业务事件（skill_start / artifact / sandbox_job / clarify）用 CUSTOM 承载，按 payload 的 {@code sseType} 映射。 */
    private static Optional<SseEvent> custom(AgentEvent event, SseEventType defaultType) {
        Object raw = event.payload().get("sseType");
        if (raw == null) {
            return Optional.of(SseEvent.of(defaultType, event.payload()));
        }
        for (SseEventType type : SseEventType.values()) {
            if (type.wireName().equals(String.valueOf(raw))) {
                return Optional.of(SseEvent.of(type, event.payload()));
            }
        }
        return Optional.of(SseEvent.of(defaultType, event.payload()));
    }

    private static String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public List<SseEvent> projectAll(List<AgentEvent> events) {
        return events.stream().map(this::project).flatMap(Optional::stream).toList();
    }
}
