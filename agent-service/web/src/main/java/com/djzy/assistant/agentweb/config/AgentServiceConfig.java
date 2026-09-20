package com.djzy.assistant.agentweb.config;

import com.djzy.assistant.agentweb.auth.Authenticator;
import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.auth.EntryTicketStore;
import com.djzy.assistant.agentweb.auth.InMemoryEntryTicketStore;
import com.djzy.assistant.agentweb.auth.RedisEntryTicketStore;
import com.djzy.assistant.agentweb.gateway.GatewayClient;
import com.djzy.assistant.agentweb.gateway.GatewayToolPlane;
import com.djzy.assistant.agentweb.service.ChatService;
import com.djzy.assistant.agentweb.service.RedisTurnLimiter;
import com.djzy.assistant.agentweb.service.TurnLimiter;
import com.djzy.assistant.agentweb.service.TurnRateLimiter;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.FileSessionJournal;
import com.djzy.assistant.agentweb.session.SessionJournal;
import com.djzy.assistant.agentweb.state.FileRuntimeStatePort;
import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.common.eventlog.AppendOnlyEventLog;
import com.djzy.assistant.common.bus.EventPersistConsumer;
import com.djzy.assistant.common.bus.EventPublisher;
import com.djzy.assistant.common.bus.EventQueue;
import com.djzy.assistant.common.bus.EventQueueMetrics;
import com.djzy.assistant.common.eventlog.EventLogConfig;
import com.djzy.assistant.common.eventlog.EventLogMetrics;
import com.djzy.assistant.common.eventlog.FileEventLogCursorStore;
import com.djzy.assistant.common.eventlog.LogFirstEventPublisher;
import com.djzy.assistant.common.eventlog.LogOnlyEventPublisher;
import com.djzy.assistant.common.persistence.JdbcAgentEventFactWriter;
import com.djzy.assistant.common.persistence.PgOutboxEventBus;
import com.djzy.assistant.common.persistence.PgRuntimeStatePort;
import com.djzy.assistant.common.persistence.RedisStreamEventQueue;
import com.djzy.assistant.common.identity.JwtTokenService;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import com.djzy.assistant.common.persistence.JdbcUserStatusPort;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.core.runtime.RuntimeRegistry;
import com.djzy.assistant.agentweb.llm.ConfiguredModelProvider;
import com.djzy.assistant.common.llm.ApiKeyCipher;
import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
import com.djzy.assistant.core.sse.SseProjector;
import com.djzy.assistant.core.sse.ThinkingVisibility;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.RuntimeStatePort;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** agent-service 装配（§12.2 / §19.4 / §19.6）。运行时、工具面、回放位全部按端口装配，便于替换实现。 */
@Configuration
public class AgentServiceConfig {

    /** 令牌**校验**用（与签发方同一密钥，§20.1.1）。 */
    @Bean
    public UserTokenVerifier userTokenVerifier(AgentServiceProperties properties) {
        return new JwtTokenService(
                StartupSecretCheck.resolverOf(properties), properties.getTokenKeyId(), properties.getTokenTtl());
    }

    @Bean
    public UserStatusPort userStatusPort(DataSource dataSource) {
        return new JdbcUserStatusPort(dataSource);
    }

    @Bean
    public Authenticator authenticator(UserTokenVerifier userTokenVerifier, UserStatusPort userStatusPort) {
        return new Authenticator(userTokenVerifier, userStatusPort);
    }

    /** 多副本必须显式选 redis：单实例内存券无法保证跨实例一次性（与 §20.1.4 同一条理由）。 */
    @Bean
    public EntryTicketStore entryTicketStore(
            AgentServiceProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getTicketStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("agent-service.ticket-store=redis 但容器中没有 StringRedisTemplate");
            }
            return new RedisEntryTicketStore(template);
        }
        return new InMemoryEntryTicketStore();
    }

    @Bean
    public EntryTicketService entryTicketService(EntryTicketStore store, AgentServiceProperties properties) {
        return new EntryTicketService(store, properties.getTicketTtl());
    }

    /** 回放位落本地卷（§19.6 同一套 append-only 纪律），卷不可用即拒绝启动。 */
    @Bean(destroyMethod = "close")
    public SessionJournal sessionJournal(AgentServiceProperties properties) {
        return new FileSessionJournal(
                Path.of(properties.getEventLogDir()), instanceId(properties), properties.getReplayBufferSize());
    }

    @Bean
    public ChatSessionRegistry chatSessionRegistry(SessionJournal sessionJournal, AgentServiceProperties properties) {
        return new ChatSessionRegistry(sessionJournal, properties.getReplayBufferSize());
    }

    /** 会话日志（唯一事实源，§19.6）：先落日志再投影 SSE。 */
    @Bean(destroyMethod = "close")
    public AppendOnlyEventLog appendOnlyEventLog(AgentServiceProperties properties) {
        EventLogConfig config = new EventLogConfig(
                Path.of(properties.getEventLogDir()),
                instanceId(properties),
                EventLogConfig.DEFAULT_MAX_FILE_BYTES,
                EventLogConfig.DEFAULT_FSYNC_BATCH_SIZE,
                EventLogConfig.DEFAULT_FSYNC_INTERVAL_MS,
                Duration.ofHours(24));
        return new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()));
    }

    /**
     * 运行时状态落点（§19.5）：默认落 PG，SSE 连接才能落到任意副本、换 pod 不丢挂起状态。
     *
     * <p>{@code agent-service.state-store=file} 只留给「没有 PG 也要跑」的骨架场景，
     * 一旦多副本，文件落点会让会话被钉在单台机器上（§2.3 隐性单点）。
     */
    @Bean
    public RuntimeStatePort runtimeStatePort(
            AgentServiceProperties properties, ObjectProvider<DataSource> dataSourceProvider) {
        if ("file".equalsIgnoreCase(properties.getStateStore())) {
            return new FileRuntimeStatePort(Path.of(properties.getStateDir()));
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("agent-service.state-store=pg 但容器里没有 DataSource");
        }
        return new PgRuntimeStatePort(dataSource);
    }

    /**
     * 落库通道（§19.6 / ADR-28）：队列只是「把事件搬到 PG 事实表的通道」，唯一事实源始终是本地
     * append-only 日志（见 {@link LogFirstEventPublisher}）。
     *
     * <ul>
     *   <li>{@code redis-stream}（规格 §19.6 的路径）：{@code XADD} 投递 / {@code XREADGROUP} 消费 /
     *       {@code XPENDING} 退避重投；死信仍写 PG 的 {@code event_dead_letter}，
     *       所以这里同样要 {@link DataSource}；
     *   <li>{@code pg-outbox}（骨架期默认）：不需要额外中间件，「只有 PG 也能跑」；
     *   <li>{@code none}：只落本地日志，不搬事实表——本地日志本来就是唯一事实源。
     * </ul>
     *
     * <p>Redis 不可用时**不让服务起不来**：投递失败由 {@link LogFirstEventPublisher} 记「等待追赶投递」
     * 并计数告警，事件已经安全地躺在本地日志里（ADR-28：Redis 故障只允许造成 PG 滞后）。
     */
    @Bean
    public EventQueue eventQueue(
            AgentServiceProperties properties,
            DataSource dataSource,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis-stream".equalsIgnoreCase(properties.getEventBus())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("agent-service.event-bus=redis-stream 但容器中没有 StringRedisTemplate");
            }
            // 消费者名用实例 id：多副本同名会把别人的待确认条目认成「自己的」（§2.3）。
            return new RedisStreamEventQueue(
                    template, dataSource, properties.getEventStream(), instanceId(properties));
        }
        if (!"pg-outbox".equalsIgnoreCase(properties.getEventBus())) {
            return null;
        }
        return new PgOutboxEventBus(dataSource);
    }

    /** 投递观测（§10.2）：骨架期只进日志，接 Prometheus / OTel 时换实现，装配处不动。 */
    @Bean
    public EventQueueMetrics eventQueueMetrics() {
        return EventQueueMetrics.noop();
    }

    /** 事件发布口（§19.6）：**先写本地 append-only 日志（唯一事实源），再入队**。 */
    @Bean
    public EventPublisher eventPublisher(
            AppendOnlyEventLog eventLog, ObjectProvider<EventQueue> queueProvider, EventQueueMetrics metrics) {
        EventQueue queue = queueProvider.getIfAvailable();
        return queue == null
                ? new LogOnlyEventPublisher(eventLog)
                : new LogFirstEventPublisher(eventLog, queue, EventLogMetrics.noop());
    }

    /**
     * 启动追赶投递（§19.6）：日志里躺着但投递未确认的事件重新入队，队列故障恢复后靠它补齐。
     * {@code event_id} 幂等，重放安全。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner redeliverPendingOnStartup(
            @org.springframework.beans.factory.annotation.Qualifier("eventPublisher") EventPublisher publisher) {
        return args -> {
            if (publisher instanceof LogFirstEventPublisher logFirst) {
                logFirst.redeliverPending();
            }
        };
    }

    /** 事实表写入（§8.3）：默认 PostgreSQL 方言；H2 测试用 {@code @Primary} 覆盖成纯文本变体。 */
    @Bean
    public com.djzy.assistant.common.bus.AgentEventFactWriter agentEventFactWriter(DataSource dataSource) {
        return new JdbcAgentEventFactWriter(dataSource);
    }

    /** event-persist 消费者（§19.6）：攒批把队列里的事件搬进事实表。 */
    @Bean
    public EventPersistConsumer eventPersistConsumer(
            ObjectProvider<EventQueue> queueProvider,
            ObjectProvider<com.djzy.assistant.common.bus.AgentEventFactWriter> writerProvider,
            EventQueueMetrics metrics) {
        EventQueue queue = queueProvider.getIfAvailable();
        if (queue == null) {
            return null;
        }
        return new EventPersistConsumer(queue, writerProvider.getObject(), metrics);
    }

    /** 骨架期默认运行时：不调模型、不出网，用于把接入层与契约跑通（§18.11.1）。 */
    @Bean
    public AgentRuntimePort noopRuntime() {
        return new com.djzy.assistant.runtime.noop.NoopRuntimeAdapter();
    }

    /**
     * 模型 API Key 的解密（§20.1.6）：密文是管理端写的，这里只负责解。
     * 未配 KEK 不阻塞启动——noop 运行时根本不碰模型；真要调模型时会明确失败。
     */
    @Bean
    public ApiKeyCipher apiKeyCipher(AgentServiceProperties properties) {
        return ApiKeyCipher.fromBase64OrUnconfigured(properties.getLlmKek(), "agent-service.llm-kek");
    }

    /** 模型供应商配置读取（ADR-14）：与管理端共用同一份 SQL，每轮直查、零缓存。 */
    @Bean
    public JdbcLlmProviderStore llmProviderStore(DataSource dataSource, ApiKeyCipher apiKeyCipher) {
        return new JdbcLlmProviderStore(dataSource, apiKeyCipher);
    }

    /**
     * 默认运行时实现（ADR-18 二次修订 / ADR-31 / ADR-32）。
     *
     * <p>**装进来 ≠ 被选中**：{@code RuntimeRegistry} 只认 {@code tck-passed-runtimes} 里的 id
     * （§18.11.6），默认仍是 noop。要真正启用：先过 TCK，再把 {@code agent-service.runtime-id}
     * 改成 {@code agentscope}——这是一次显式、可回退的切换，而不是加了依赖就悄悄换引擎。
     *
     * <p>状态存储用框架自带的 JSON 文件实现（落在平台 {@code state-dir} 下的子目录）：
     * 单副本骨架够用；多副本 / 换 pod 恢复要 PG 实现，与 §19.5「状态落 PG」是同一笔后续账。
     */
    @Bean
    public AgentRuntimePort agentscopeRuntime(JdbcLlmProviderStore store, AgentServiceProperties properties) {
        com.djzy.assistant.runtime.agentscope.ModelProvider modelProvider = new ConfiguredModelProvider(
                store, () -> "raw_reasoning".equalsIgnoreCase(properties.getThinkingVisibility()));
        io.agentscope.core.state.AgentStateStore stateStore =
                new io.agentscope.core.state.JsonFileAgentStateStore(
                        java.nio.file.Path.of(properties.getStateDir(), "agentscope"));
        return new com.djzy.assistant.runtime.agentscope.AgentscopeRuntimeAdapter(
                modelProvider,
                stateStore,
                // HarnessAgent 的工作目录：默认按 cwd 建 .agentscope/，会把会话原文写进应用启动目录。
                // 钉到 state-dir 下，运行时自己的痕迹（若有）只落在平台管得到的地方。
                java.nio.file.Path.of(properties.getStateDir(), "agentscope-workspace"));
    }

    /**
     * 运行时准入（§18.11.6）：只有通过 TCK 的实现才会被选中；未通过即回退，绝不放行未验证实现。
     * 加 runtime-agentscope 时：把它加入依赖、注册 {@code AgentRuntimePort} bean，并在 TCK 通过后列入名单。
     */
    @Bean
    public RuntimeRegistry runtimeRegistry(ObjectProvider<AgentRuntimePort> runtimes, AgentServiceProperties properties) {
        List<AgentRuntimePort> available = runtimes.orderedStream().toList();
        List<String> tckPassed = available.stream()
                .map(AgentRuntimePort::id)
                .filter(properties.getTckPassedRuntimes()::contains)
                .toList();
        return new RuntimeRegistry(available, tckPassed, properties.getRuntimeId());
    }

    @Bean
    public SseProjector sseProjector(AgentServiceProperties properties) {
        ThinkingVisibility visibility = "raw_reasoning".equalsIgnoreCase(properties.getThinkingVisibility())
                ? ThinkingVisibility.RAW_REASONING
                : ThinkingVisibility.STEP_CARD_ONLY;
        return new SseProjector(() -> visibility);
    }

    /** 工具面：配了网关地址就接数据面；否则为空清单（运行时调不到任何工具，而不是「都能调」）。 */
    @Bean
    public ToolPlane toolPlane(AgentServiceProperties properties) {
        if (!properties.gatewayConfigured()) {
            return ToolPlane.empty();
        }
        ServiceCredential credential = new ServiceCredential(
                properties.getGatewayKeyId(), properties.getSecrets().getOrDefault(properties.getGatewayKeyId(), ""));
        GatewayClient client = new GatewayClient(
                properties.getGatewayUrl(), credential, Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new GatewayToolPlane(client);
    }

    @Bean
    public TurnLimiter turnLimiter(
            AgentServiceProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getRateLimitStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("agent-service.rate-limit-store=redis 但容器中没有 StringRedisTemplate");
            }
            return new RedisTurnLimiter(template, properties.getTurnsPerMinute());
        }
        return new TurnRateLimiter(properties.getTurnsPerMinute());
    }

    @Bean
    public ChatService chatService(
            ChatSessionRegistry registry,
            EntryTicketService tickets,
            RuntimeRegistry runtimes,
            ToolPlane toolPlane,
            RuntimeStatePort statePort,
            SseProjector projector,
            TurnLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Qualifier("eventPublisher") EventPublisher events,
            AgentServiceProperties properties) {
        return new ChatService(
                registry, tickets, runtimes, toolPlane, statePort, projector, rateLimiter, events, properties);
    }

    static String instanceId(AgentServiceProperties properties) {
        if (properties.getInstanceId() != null && !properties.getInstanceId().isBlank()) {
            return properties.getInstanceId();
        }
        String host = "local";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 主机名取不到不影响唯一性，端口/随机后缀仍然区分实例
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}
