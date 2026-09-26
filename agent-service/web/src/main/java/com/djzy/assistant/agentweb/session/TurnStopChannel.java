package com.djzy.assistant.agentweb.session;

import io.agentscope.harness.agent.bus.MessageBus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

/**
 * 「这一轮请停下来」的**推送口**（H-09）：别的实例一点停止，本实例当场把这一轮取消掉。
 *
 * <h2>它补的是哪一段</h2>
 *
 * <p>停止这件事本来就能跨实例：{@link TurnStopSignalStore} 会把信号写进共享存储，
 * 真正在跑那一轮的实例**在下一个流事件上**看到它，然后取消运行时。正确性没问题，
 * 但延迟取决于「下一个事件什么时候来」——模型正卡在一次很慢的工具调用上时，
 * 用户点了停止，界面上要愣好一会儿才停（台账 L-07 记的就是这一条）。
 *
 * <p>现在多一条路：点停止的实例顺手往共享总线上推一条消息，**每个实例都常驻订阅**，
 * 收到就把本地那一轮取消掉，不用等下一个事件。两条路同时存在、互不依赖：
 * <ul>
 *   <li><b>推送</b>负责快：能不能收到取决于订阅在不在线（单实例内存版、订阅断线时收不到）；</li>
 *   <li><b>信号键</b>负责一定能到：接不到推送时，下一个流事件照样会看到那个带 TTL 的键（就是原来那套）。</li>
 * </ul>
 * 也就是说：推送丢了不会「停不下来」，只会「慢一点」——这正是它能做得这么简单的原因。
 *
 * <h2>为什么底座用框架的 {@link MessageBus}，不自己写一套 Redis Pub/Sub</h2>
 *
 * <p>平台已经有这条总线的两套实现（{@code RedisMessageBus} / {@code InMemoryMessageBus}，H-01 落地的），
 * 连订阅容器与停机都在里面处理好了。停止推送需要的正是「指定频道、推一条、订一条」这三件事，
 * 所以这里只做一个**薄适配**：把「哪个用户的哪一轮」装进信封，别的都交给总线。
 * 换来的是：不用多一套 Redis 连接管理、不用多一套配置开关，多副本该有的行为它已经验证过。
 *
 * <p>随之而来的一个口径：**推送的传输跟着 {@code agent-service.live-bus} 走**（默认 redis）。
 * 单机开发把它设成 {@code memory} 时，推送就只在本进程里生效——跨实例停止仍然成立（走信号键），
 * 只是回到「下一个事件才停」的旧延迟。这两件事的关系写在 {@code application.yml} 里。
 */
public final class TurnStopChannel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnStopChannel.class);

    /**
     * 频道名。带 {@code agent-service} 前缀是纪律：同一个 Redis 上可能有别的系统在跑，
     * 频道名和键一样必须自带归属（与轮次闸门、事件队列同一条规矩）。
     */
    private static final String CHANNEL = "agent-service:turn-stop";

    /** 信封字段。三个都是必填，缺一个就不知道该停谁的哪一轮。 */
    private static final String FIELD_USER = "userId";
    private static final String FIELD_SESSION = "sessionId";
    private static final String FIELD_TURN = "turnId";

    private final MessageBus bus;

    /**
     * 收到推送后要做什么。正常部署只有一个（{@code ChatService} 在构造时注册自己那一个）。
     *
     * <p>做成「可以有几个」而不是「只能有一个」：订阅这件事本身不排他——谁关心停止谁就订，
     * 多一个订阅者只是多收到同样一条消息，而收到之后做什么（要不要取消）各自会判断。
     * 好处是不给以后挖坑：万一进程里出现了第二个消费停止的地方，它不会莫名其妙收不到消息。
     */
    private final List<TurnStopHandler> handlers = new CopyOnWriteArrayList<>();

    /** 订阅句柄：只为停机时能干净地退订，业务上不需要它。 */
    private volatile Disposable subscription;

    public TurnStopChannel(MessageBus bus) {
        this.bus = bus;
    }

    /**
     * 注册「收到推送后做什么」。装配期调用（{@code ChatService} 构造时注册自己那一个）。
     *
     * <p>多个订阅者之间互不影响：都会收到每一条消息。订阅本身只在**第一次**注册时建立，
     * 后面的注册只是往名单里加一个人。
     */
    public synchronized void subscribe(TurnStopHandler handler) {
        if (handler == null) {
            return;
        }
        handlers.add(handler);
        if (subscription != null) {
            return;
        }
        subscription = bus.subscribe(CHANNEL)
                .subscribe(this::dispatch, error -> log.warn(
                        "停止推送订阅中断（跨实例停止会退回按键轮询）：{}", error.toString()));
    }

    /**
     * 推一条「请停掉这一轮」。
     *
     * <p>**失败不让停止接口失败**：推送只是「更快」的那条路，真正的兜底是带 TTL 的信号键，
     * 它已经在这之前写好了。所以这里只记一条 warn。
     */
    public void publish(String userId, String sessionId, String turnId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(FIELD_USER, userId);
        envelope.put(FIELD_SESSION, sessionId);
        envelope.put(FIELD_TURN, turnId);
        try {
            bus.publish(CHANNEL, envelope).subscribe(ignored -> {}, error -> log.warn(
                    "停止推送失败（将退回按键轮询，停得掉但慢一点）：sessionId={} turnId={}",
                    sessionId, turnId, error));
        } catch (RuntimeException e) {
            log.warn("停止推送失败（将退回按键轮询，停得掉但慢一点）：sessionId={} turnId={}", sessionId, turnId, e);
        }
    }

    /** 停机时退订：连接本来由总线自己管，这里只是不给它留一个还要回调到已关停组件的订阅。 */
    @Override
    public void close() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
    }

    /**
     * 把收到的信封交给处理器。
     *
     * <p>**在这里吞掉所有异常**：这条消息是别的实例推来的，处理不了（字段缺了、本实例已经停机）
     * 也只是「这一条推送白推了」，信号键那条路仍然兜得住。让异常冒到订阅线程上没有任何好处。
     */
    private void dispatch(Map<String, Object> envelope) {
        try {
            Object userId = envelope.get(FIELD_USER);
            Object sessionId = envelope.get(FIELD_SESSION);
            Object turnId = envelope.get(FIELD_TURN);
            if (userId == null || sessionId == null || turnId == null) {
                log.warn("收到字段不全的停止推送，忽略：{}", envelope);
                return;
            }
            for (TurnStopHandler handler : handlers) {
                handler.onStop(String.valueOf(userId), String.valueOf(sessionId), String.valueOf(turnId));
            }
        } catch (RuntimeException e) {
            log.warn("处理停止推送失败（那一轮仍会靠信号键在下一个事件上停下来）：{}", e.toString());
        }
    }

    /** 收到停止推送后要做什么：参数依次是「哪个用户、哪个会话、哪一轮」。 */
    @FunctionalInterface
    public interface TurnStopHandler {

        void onStop(String userId, String sessionId, String turnId);
    }
}
