package com.djzy.assistant.agentweb.session.bus;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 进程内的消息总线：框架 {@link MessageBus} 的**单实例**实现（本机开发 / 单元测试用）。
 *
 * <p>它的存在只为一件小事：让「跨副本实时续看」这条链路在**没有 Redis 的环境里也能跑同一份代码**。
 * 单实例下当然没有「另一个副本」要同步，那份实时数据自然读不到东西——于是接流自动退回
 * 「等这一轮跑完再整段补」的老路（{@code CrossInstanceTurnRelay} 的兜底）。
 * 换句话说：换了它，行为退化成改造前的样子，但**不会静默出错**。
 *
 * <p>多副本必须换成 {@link RedisMessageBus}（见 application.yml 的 {@code agent-service.live-bus}）：
 * 内存版天然只在**本进程**里可见，另一台实例上的那一轮在这里一条也读不到。
 *
 * <p>写入是同步的：调用返回时数据已经在内存里躺着，返回的 {@code Mono} 只是接口形状
 * （框架的会话事件逻辑是「追加一条 → 通知一声」的顺序操作，异步化只会让顺序变得不确定）。
 */
public final class InMemoryMessageBus implements MessageBus {

    /** 收件箱（队列）：框架的 inboxPush / inboxDrain 走这里。 */
    private final Map<String, ConcurrentLinkedDeque<BusEntry>> queues = new ConcurrentHashMap<>();

    /** 事件日志：追加不删（只按上限裁老条目），读的时候按条目号取「游标之后」的那些。 */
    private final Map<String, ConcurrentLinkedDeque<BusEntry>> logs = new ConcurrentHashMap<>();

    /** 订阅频道：每条频道一个广播口，没有订阅者时发出去的消息直接丢掉（与 Redis 的 pub/sub 同义）。 */
    private final Map<String, Sinks.Many<Map<String, Object>>> channels = new ConcurrentHashMap<>();

    private final AtomicLong seq = new AtomicLong();

    @Override
    public Mono<String> queuePush(String queue, Map<String, Object> payload) {
        String entryId = BusEntryId.format(seq.incrementAndGet());
        queueOf(queue).addLast(new BusEntry(entryId, payload));
        return Mono.just(entryId);
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String queue, int max) {
        ConcurrentLinkedDeque<BusEntry> entries = queueOf(queue);
        List<BusEntry> drained = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            BusEntry entry = entries.pollFirst();
            if (entry == null) {
                break;
            }
            drained.add(entry);
        }
        return Mono.just(drained);
    }

    @Override
    public Mono<Void> queueDelete(String queue) {
        queues.remove(queue);
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> queuePeek(String queue) {
        return Mono.just(!queueOf(queue).isEmpty());
    }

    @Override
    public Mono<String> logAppend(String log, Map<String, Object> payload, int maxLen) {
        String entryId = BusEntryId.format(seq.incrementAndGet());
        ConcurrentLinkedDeque<BusEntry> entries = logOf(log);
        entries.addLast(new BusEntry(entryId, payload));
        while (maxLen > 0 && entries.size() > maxLen) {
            entries.pollFirst();
        }
        return Mono.just(entryId);
    }

    @Override
    public Mono<List<BusEntry>> logRead(String log, String since, int limit) {
        List<BusEntry> found = new ArrayList<>();
        for (BusEntry entry : logOf(log)) {
            if (BusEntryId.isAfter(entry.entryId(), since)) {
                found.add(entry);
                if (limit > 0 && found.size() >= limit) {
                    break;
                }
            }
        }
        return Mono.just(found);
    }

    @Override
    public Mono<Void> logTrim(String log) {
        logs.remove(log);
        return Mono.empty();
    }

    @Override
    public Mono<Void> publish(String channel, Map<String, Object> payload) {
        channelOf(channel).tryEmitNext(payload);
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String channel) {
        return channelOf(channel).asFlux();
    }

    private ConcurrentLinkedDeque<BusEntry> queueOf(String queue) {
        return queues.computeIfAbsent(queue, key -> new ConcurrentLinkedDeque<>());
    }

    private ConcurrentLinkedDeque<BusEntry> logOf(String log) {
        return logs.computeIfAbsent(log, key -> new ConcurrentLinkedDeque<>());
    }

    private Sinks.Many<Map<String, Object>> channelOf(String channel) {
        return channels.computeIfAbsent(channel, key -> Sinks.many().multicast().directBestEffort());
    }
}
