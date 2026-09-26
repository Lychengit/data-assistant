package com.djzy.assistant.agentweb.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.djzy.assistant.agentstate.PlatformLiveTurnState;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentstate.PlatformSessionStore;
import com.djzy.assistant.agentweb.session.bus.InMemoryMessageBus;
import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 跨实例接流（T1-09 / DR-09）：本实例没有这一轮时，接流要「等得到、说得清、不空挂」。
 *
 * <p>三个结局各一条用例，都是**用户视角**的断言（收到哪些事件），不是内部状态：
 * <ul>
 *   <li>别的实例跑完了 → 整轮结果（含 {@code done}）都补过来；</li>
 *   <li>别的实例已经没人管了 → 「这一轮没跑完，请重发」，并把原话留住；</li>
 *   <li>等到窗口结束还在跑 → 明确说「还在处理中」，并闭合这一轮（绝不能挂着不说话）。</li>
 * </ul>
 *
 * <p>不用 Spring：这里要验的是「等待 + 投影回放」这一段逻辑，状态库用框架自带的内存实现，
 * 轮询间隔压到几十毫秒，用例总耗时仍是毫秒级。
 */
class CrossInstanceTurnRelayTest {

    private static final String USER = "alice";
    private static final String SESSION = "s-1";

    private final AgentStateStore stateStore = new InMemoryAgentStateStore();
    private final SessionCatalog catalog = new SessionCatalog(
            stateStore, new PlatformSessionStore(stateStore), new PlatformLiveTurnStore(stateStore));
    private final InMemorySessionTurnGate turnGate =
            new InMemorySessionTurnGate(Duration.ofMinutes(1));

    /** 共享总线：用例里的「另一个实例」往它上面写，接流这一侧从它上面追（H-01）。 */
    private final InMemoryMessageBus bus = new InMemoryMessageBus();

    private final LiveTurnChannel liveChannel = new LiveTurnChannel(bus, 200);

    /** 追实时 20ms、看状态 20ms、最多等 400ms：够跑几次探测，又不会让用例变慢。 */
    private final CrossInstanceTurnRelay relay = new CrossInstanceTurnRelay(
            catalog,
            turnGate,
            liveChannel,
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(400),
            Duration.ofMinutes(1));

    @Test
    void 别的实例已经没人管了_如实说没跑完并把原话留住() {
        PlatformLiveTurnState live =
                new PlatformLiveTurnState("turn-dead", "instance-that-died", "急诊科上个月的留观人数", now() - 600_000L);
        // 坑位空着（进程没了）+ 标记很旧 → 判定为「已经没人管了」

        List<SseEvent> events = relay.watch(USER, SESSION, live).collectList().block(Duration.ofSeconds(5));

        assertThat(names(events)).containsExactly("user", "error", "done");
        assertThat(events.get(0).payload()).containsEntry("text", "急诊科上个月的留观人数");
        assertThat(events.get(1).payload()).containsEntry("code", SessionTranscript.TURN_INTERRUPTED_CODE);
        assertThat(events.get(2).payload()).containsEntry("turnId", "turn-dead");
    }

    @Test
    void 等不到结果_也要闭合这一轮并说清原因() throws Exception {
        // 坑位一直有人占着（别的实例还在跑），等到窗口结束也等不到
        PlatformLiveTurnState live = new PlatformLiveTurnState("turn-slow", "instance-a", "很慢的一轮", now());
        // 假装这一轮正被别的实例占着：占上就不放（用例结束进程内实例就没了，不需要手动清理）
        turnGate.acquire(TurnGateKeys.of(USER, SESSION));

        List<SseEvent> events = relay.watch(USER, SESSION, live).collectList().block(Duration.ofSeconds(5));

        assertThat(names(events)).containsExactly("user", "error", "done");
        assertThat(events.get(1).payload()).containsEntry("code", CrossInstanceTurnRelay.TURN_STILL_RUNNING_CODE);
    }

    @Test
    void 别的实例跑完_补过来的是整轮投影且以done收尾() throws Exception {
        PlatformLiveTurnState live = new PlatformLiveTurnState("turn-live", "instance-a", "心内科上个月的门诊量", now());
        turnGate.acquire(TurnGateKeys.of(USER, SESSION));
        // 探测期间「别的实例」把这一轮写进共享状态：一轮提问 + 一句回答
        Thread writer = new Thread(() -> {
            sleep(80);
            stateStore.save(
                    USER,
                    SESSION,
                    SessionCatalog.AGENT_STATE_KEY,
                    AgentState.builder()
                            .sessionId(SESSION)
                            .userId(USER)
                            .context(List.of(user("心内科上个月的门诊量"), assistant("上个月 1200 人次。")))
                            .build());
        });
        writer.start();

        List<SseEvent> events = relay.watch(USER, SESSION, live).collectList().block(Duration.ofSeconds(5));
        join(writer);

        assertThat(names(events)).containsExactly("user", "token", "done");
        assertThat(events.get(0).payload()).containsEntry("text", "心内科上个月的门诊量");
        assertThat(events.get(1).payload()).containsEntry("delta", "上个月 1200 人次。");
    }

    /**
     * H-01 的主路径：别的实例**边跑边写**共享总线，接流这一侧就边跑边发；
     * 这一轮跑完时（对方的 done 也在总线上）**不再整段补一遍**——否则用户会把同一段回答看两遍。
     */
    @Test
    void 别的实例正在产出的内容会实时补上来_跑完时不重复整段补() throws Exception {
        PlatformLiveTurnState live = new PlatformLiveTurnState("turn-live", "instance-a", "急诊科留观人数", now());
        // 这一轮正被别的实例占着（它才会往总线上写）
        turnGate.acquire(TurnGateKeys.of(USER, SESSION));
        Thread writer = new Thread(() -> {
            sleep(30);
            liveChannel.publishTurnEvent(USER, SESSION, "turn-live", token("上个月"));
            sleep(30);
            liveChannel.publishTurnEvent(USER, SESSION, "turn-live", token(" 36 人次。"));
            sleep(30);
            // 跑完：别的实例会把 done 也写进共享总线，接流这一侧据此收尾
            liveChannel.publishTurnEvent(
                    USER, SESSION, "turn-live", SseEvent.of(SseEventType.DONE, Map.of("turnId", "turn-live")));
        });
        writer.start();

        List<SseEvent> events = relay.watch(USER, SESSION, live).collectList().block(Duration.ofSeconds(5));
        join(writer);

        assertThat(names(events)).containsExactly("user", "token", "token", "done");
        assertThat(events.get(1).payload()).containsEntry("delta", "上个月");
        assertThat(events.get(2).payload()).containsEntry("delta", " 36 人次。");
    }

    private static SseEvent token(String delta) {
        return SseEvent.of(SseEventType.TOKEN, Map.of("delta", delta));
    }

    private static List<String> names(List<SseEvent> events) {
        return events.stream().map(event -> event.type().wireName()).toList();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static Msg user(String text) {
        return UserMessage.builder().content(List.of(TextBlock.builder().text(text).build())).build();
    }

    private static Msg assistant(String text) {
        return AssistantMessage.builder().content(List.of(TextBlock.builder().text(text).build())).build();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
