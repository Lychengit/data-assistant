package com.djzy.assistant.agentweb.session.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 跨副本的消息总线：框架 {@link MessageBus} 的 **Redis** 实现。
 *
 * <h2>为什么要我们自己写一个</h2>
 *
 * <p>框架 2.0.3 只带了**一个** {@code MessageBus} 实现——{@code WorkspaceMessageBus}，
 * 它把消息写进「工作区文件系统」（{@code AbstractFilesystem}）里。那个实现服务的对象是
 * agent **内部**的消息：收件箱（{@code InboxMiddleware}）、异步工具、子代理、团队。
 * 这些能力平台一律关闭（ADR-31 基线：不让模型偷偷多跑），所以它对我们没用。
 *
 * <p>平台要用总线的地方是**接入层**：一轮对话正在 A 实例上跑，用户断线重连被丢到 B 实例，
 * B 要看得到这一轮正在产出的内容（T1-09 的实时版 = H-01）。这条路是「会话时间线上的事件」
 * ——正好是框架 {@code MessageBus} 里那组 {@code sessionPublishEvent / sessionReadEvents} 的语义，
 * 所以这里实现框架的接口、复用框架那几条默认方法，只是把**底座**换成 Redis。
 *
 * <h2>为什么这些方法都是「同步」的</h2>
 *
 * <p>本实现的每一次读写都在**调用线程上直接做完**，返回的 {@code Mono} 已经带着结果。
 * 理由有两条，都不是偷懒：
 * <ul>
 *   <li>框架的会话事件逻辑是「先追加一条、再通知一声」的顺序操作，异步化只会让顺序变得不确定；</li>
 *   <li>调用它的地方本来就在做本地顺序写（见 {@code LogFirstEventPublisher}：事件先追加写本地日志），
 *       再加一次 Redis 写入并不改变这件事的性质。</li>
 * </ul>
 *
 * <p>反过来，{@link #subscribe} 是真异步的（Redis 的订阅推送），但平台目前的实时链路走的是
 * 「追日志」（{@code LiveTurnChannel} 按游标读 + 轮询），因为它天然不重不漏、不需要处理
 * 「先订阅还是先补历史」这个竞态。这里实现完整，是为了接口上不留坑（默认方法会用到它）。
 *
 * <h2>键长什么样</h2>
 *
 * <p>{@code <前缀>q:<名字>} 队列 / {@code <前缀>log:<名字>} 日志 / {@code <前缀>ch:<名字>} 频道。
 * 日志与队列都带 TTL（默认见 {@code AgentServiceConfig}）：它们是「这一轮正在跑」的临时数据，
 * 不是会话正文的存档——会话正文的权威始终是 PG 里的框架状态（§19.5）。
 */
public final class RedisMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageBus.class);

    /** 条目在 Redis 里的形状：{@code {"entryId":"...","payload":{...}}}（入库的 JSON 必须自描述，排障时直接可读）。 */
    private static final String FIELD_ENTRY_ID = "entryId";
    private static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate template;
    private final RedisConnectionFactory connectionFactory;
    private final String keyPrefix;
    private final Duration ttl;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 订阅频道 → 广播口；没有订阅者的频道不占资源。 */
    private final Map<String, Sinks.Many<Map<String, Object>>> channels = new ConcurrentHashMap<>();

    /** 订阅用的监听容器：只有真有人订阅时才建（建它就要开一条连接，不该在装配期白开）。 */
    private volatile RedisMessageListenerContainer listener;

    public RedisMessageBus(
            StringRedisTemplate template, RedisConnectionFactory connectionFactory, String keyPrefix, Duration ttl) {
        this.template = template;
        this.connectionFactory = connectionFactory;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public Mono<String> queuePush(String queue, Map<String, Object> payload) {
        String entryId = nextEntryId();
        String key = queueKey(queue);
        template.opsForList().rightPush(key, encode(entryId, payload));
        template.expire(key, ttl);
        return Mono.just(entryId);
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String queue, int max) {
        String key = queueKey(queue);
        List<String> raw = template.opsForList().range(key, 0, max - 1L);
        if (raw == null || raw.isEmpty()) {
            return Mono.just(List.of());
        }
        // 先读再裁：两步不是原子的。队列这条路平台目前没用（收件箱能力是关掉的），
        // 真要用时这里该换成一条 Lua —— 与 RedisSessionTurnGate 释放坑位那里同一个理由。
        template.opsForList().trim(key, max, -1);
        return Mono.just(decodeAll(raw));
    }

    @Override
    public Mono<Void> queueDelete(String queue) {
        template.delete(queueKey(queue));
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> queuePeek(String queue) {
        Long size = template.opsForList().size(queueKey(queue));
        return Mono.just(size != null && size > 0);
    }

    @Override
    public Mono<String> logAppend(String log, Map<String, Object> payload, int maxLen) {
        String entryId = nextEntryId();
        String key = logKey(log);
        template.opsForList().rightPush(key, encode(entryId, payload));
        if (maxLen > 0) {
            template.opsForList().trim(key, -maxLen, -1);
        }
        // TTL 每次追加都续一次：日志是「这一轮正在跑」的临时数据，跑完一段时间后自己消失，
        // 不需要任何清理任务。
        template.expire(key, ttl);
        return Mono.just(entryId);
    }

    @Override
    public Mono<List<BusEntry>> logRead(String log, String since, int limit) {
        List<String> raw = template.opsForList().range(logKey(log), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return Mono.just(List.of());
        }
        List<BusEntry> found = new ArrayList<>();
        for (String json : raw) {
            BusEntry entry = decode(json);
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
        template.delete(logKey(log));
        return Mono.empty();
    }

    @Override
    public Mono<Void> publish(String channel, Map<String, Object> payload) {
        template.convertAndSend(channelKey(channel), writeJson(payload));
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String channel) {
        Sinks.Many<Map<String, Object>> sink = channels.computeIfAbsent(
                channel, key -> Sinks.many().multicast().onBackpressureBuffer(256));
        MessageListener listenerForChannel = (message, pattern) -> sink.tryEmitNext(
                readJson(new String(message.getBody(), StandardCharsets.UTF_8)));
        container().addMessageListener(listenerForChannel, new ChannelTopic(channelKey(channel)));
        return sink.asFlux();
    }

    /** 停机：把订阅容器关掉（它自己也占一条连接与一个线程）。 */
    @Override
    public void close() {
        RedisMessageListenerContainer current = listener;
        if (current == null) {
            return;
        }
        try {
            // destroy() 自己会先 stop：关不掉只记一条日志——这时进程正在退出，
            // 因为清理失败把停机搞成异常是最没价值的一种失败。
            current.destroy();
        } catch (Exception e) {
            log.warn("关闭总线订阅容器失败（进程正在退出，不影响任何正确性）", e);
        }
    }

    /** 全局自增号：跨实例、跨频道都唯一，所以「谁的条目更新」在任何一台机器上都有同一个答案。 */
    private String nextEntryId() {
        Long value = template.opsForValue().increment(keyPrefix + "seq");
        if (value == null) {
            throw new IllegalStateException("Redis 没有返回自增号，无法为总线条目发号");
        }
        return BusEntryId.format(value);
    }

    private synchronized RedisMessageListenerContainer container() {
        if (listener == null) {
            RedisMessageListenerContainer created = new RedisMessageListenerContainer();
            created.setConnectionFactory(connectionFactory);
            created.afterPropertiesSet();
            created.start();
            listener = created;
        }
        return listener;
    }

    private String encode(String entryId, Map<String, Object> payload) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(FIELD_ENTRY_ID, entryId);
        wrapped.put(FIELD_PAYLOAD, payload == null ? Map.of() : payload);
        return writeJson(wrapped);
    }

    @SuppressWarnings("unchecked")
    private BusEntry decode(String json) {
        Map<String, Object> wrapped = readJson(json);
        Object payload = wrapped.get(FIELD_PAYLOAD);
        return new BusEntry(
                String.valueOf(wrapped.get(FIELD_ENTRY_ID)),
                payload instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }

    private List<BusEntry> decodeAll(List<String> raw) {
        List<BusEntry> entries = new ArrayList<>(raw.size());
        for (String json : raw) {
            entries.add(decode(json));
        }
        return entries;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("总线条目序列化失败", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("总线条目反序列化失败", e);
        }
    }

    private String queueKey(String queue) {
        return keyPrefix + "q:" + queue;
    }

    private String logKey(String log) {
        return keyPrefix + "log:" + log;
    }

    private String channelKey(String channel) {
        return keyPrefix + "ch:" + channel;
    }
}
