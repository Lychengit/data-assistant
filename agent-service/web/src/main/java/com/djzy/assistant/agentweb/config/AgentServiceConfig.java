package com.djzy.assistant.agentweb.config;

import com.djzy.assistant.agentweb.auth.EntryTicketService;
import com.djzy.assistant.agentweb.auth.EntryTicketStore;
import com.djzy.assistant.agentweb.auth.InMemoryEntryTicketStore;
import com.djzy.assistant.agentweb.auth.RedisEntryTicketStore;
import com.djzy.assistant.agentweb.session.InMemoryTurnStopSignalStore;
import com.djzy.assistant.agentweb.session.LiveTurnChannel;
import com.djzy.assistant.agentweb.session.bus.InMemoryMessageBus;
import com.djzy.assistant.agentweb.session.bus.RedisMessageBus;
import com.djzy.assistant.agentweb.session.RedisTurnStopSignalStore;
import com.djzy.assistant.agentweb.session.InMemorySessionTurnGate;
import com.djzy.assistant.agentweb.session.RedisSessionTurnGate;
import com.djzy.assistant.agentweb.session.TurnStopSignalStore;
import com.djzy.assistant.agentweb.session.TurnStopChannel;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import com.djzy.assistant.agentweb.gateway.GatewayClient;
import com.djzy.assistant.agentweb.gateway.GatewayToolPlane;
import com.djzy.assistant.agentweb.service.ChatService;
import com.djzy.assistant.agentweb.service.RedisTurnLimiter;
import com.djzy.assistant.agentweb.service.TurnLimiter;
import com.djzy.assistant.agentweb.service.TurnRateLimiter;
import com.djzy.assistant.agentweb.skill.RegistrySkillPackageSource;
import com.djzy.assistant.agentweb.skill.SkillPackageSource;
import com.djzy.assistant.agentweb.skill.WorkspaceSkillProvisioner;
import com.djzy.assistant.agentweb.session.ChatSessionRegistry;
import com.djzy.assistant.agentweb.session.CrossInstanceTurnRelay;
import com.djzy.assistant.agentweb.session.SessionCatalog;
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
import com.djzy.assistant.common.persistence.RedisStreamEventQueue;
import com.djzy.assistant.common.identity.JwtTokenService;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import com.djzy.assistant.common.persistence.JdbcUserStatusPort;
import com.djzy.assistant.common.web.auth.JwtUserContextResolver;
import com.djzy.assistant.common.web.auth.UserContextResolver;
import com.djzy.assistant.common.web.auth.UserContextWebConfigurer;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.storage.ObjectStorage;
import com.djzy.assistant.common.storage.s3.ObjectStorageConfig;
import com.djzy.assistant.core.runtime.RuntimeRegistry;
import com.djzy.assistant.agentweb.llm.ConfiguredModelProvider;
import com.djzy.assistant.common.llm.ApiKeyCipher;
import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
import com.djzy.assistant.core.sse.SseProjector;
import com.djzy.assistant.core.sse.ThinkingVisibility;
import com.djzy.assistant.agentstate.PlatformAgentStateStore;
import com.djzy.assistant.agentstate.PlatformSandboxExecutionGuard;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.agentstate.PlatformLiveTurnStore;
import com.djzy.assistant.agentstate.PlatformSessionStore;
import com.djzy.assistant.agentstate.PlatformTurnStore;
import com.djzy.assistant.agentstate.PlatformWorkspaceStore;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** agent-service 装配（§12.2 / §19.4 / §19.6）。运行时、工具面、回放位全部按端口装配，便于替换实现。 */
@Configuration
// 对象存储装配直接复用公共那份（provider=local / s3 的开关也只有一个）：技能内容要从这里取（H-06a）。
@Import(ObjectStorageConfig.class)
public class AgentServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceConfig.class);

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

    /**
     * 身份解析口子：「验登录令牌 + 查账号状态」。
     *
     * <p>原来这段逻辑散在控制器里（每个接口都要写一遍 authenticator.requireUser(...)），
     * 现在收到一处；控制器只声明 {@code @CurrentUser} 参数。
     */
    @Bean
    public UserContextResolver agentUserContextResolver(
            UserTokenVerifier userTokenVerifier, UserStatusPort userStatusPort) {
        return new JwtUserContextResolver(userTokenVerifier, userStatusPort);
    }

    /**
     * 装上身份拦截器 + {@code @CurrentUser} 参数解析器：agent-service 的对外接口都在 {@code /v1/**}。
     *
     * <p>默认全都要身份（fail-closed）；确实靠别的凭证进门的接口（例如靠一次性券建立的 SSE 连接），
     * 在控制器方法上打 {@code @AnonymousAccess} 显式放开。
     */
    @Bean
    public UserContextWebConfigurer agentUserContextWebConfigurer(UserContextResolver resolver) {
        return new UserContextWebConfigurer(resolver, List.of("/v1/**"));
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

    /**
     * 停止信号通道：多副本必须走 Redis。
     *
     * <p>与入场券同一条理由——内存版只能在单实例内生效，多副本下会变成「有时停得掉、有时停不掉」，
     * 这种偶发失效比直接报错更难查，所以宁可启动时明确选。
     */
    @Bean
    public TurnStopSignalStore turnStopSignalStore(
            AgentServiceProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getStopSignalStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("agent-service.stop-signal-store=redis 但容器中没有 StringRedisTemplate");
            }
            return new RedisTurnStopSignalStore(template);
        }
        return new InMemoryTurnStopSignalStore();
    }

    /**
     * 停止的**推送口**（H-09）：点停止的实例推一条，持有这一轮的那台机器收到就当场取消，
     * 不用等下一个流事件（原来只有「带 TTL 的信号键」，延迟看下一个事件什么时候来）。
     *
     * <p>底座直接用 H-01 那条共享总线（{@code agent-service.live-bus}，默认 Redis），不另起一套 Pub/Sub：
     * 「推一条、订一条」正是总线已经做好的事，连订阅容器与停机都在里面处理过了。
     *
     * <p>于是有一个要记住的口径：**推送的传输跟着 {@code live-bus} 走**。把它设成 {@code memory} 时，
     * 推送只在本进程内生效——跨实例停止仍然成立（走信号键），只是回到「下一个事件才停」的旧延迟。
     * 这两条路的关系写在 {@link com.djzy.assistant.agentweb.session.TurnStopChannel} 的类注释里。
     */
    @Bean(destroyMethod = "close")
    public TurnStopChannel turnStopChannel(MessageBus liveMessageBus) {
        return new TurnStopChannel(liveMessageBus);
    }

    /**
     * 轮次闸门（T1-07）：同一个会话**跨实例**同时只跑一轮。
     *
     * <p>为什么和停止信号一样按存储分成两套实现：它俩都是「共享的一点点状态」，
     * 都需要「多副本必须 redis、单机可以 memory」这条同样的取舍，所以开关的写法保持一致，
     * 运维不用记两套。默认 {@code redis}：忘了配也不会退化成静默错误（配上 Redis 是既定前提）。
     */
    @Bean
    public SessionTurnGate sessionTurnGate(
            AgentServiceProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getTurnGateStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("agent-service.turn-gate-store=redis 但容器中没有 StringRedisTemplate");
            }
            return new RedisSessionTurnGate(template, properties.turnLease());
        }
        return new InMemorySessionTurnGate(properties.turnLease());
    }

    @Bean
    public EntryTicketService entryTicketService(EntryTicketStore store, AgentServiceProperties properties) {
        return new EntryTicketService(store, properties.getTicketTtl());
    }

    /**
     * 会话句柄登记簿：只放**进程内**的活口（当前运行时句柄 + 最近一段事件的回放位）。
     *
     * <p>它不再是「会话存在与否」的依据——那件事看状态库（§19.5）。会话台账落本地文件的那套已经删掉：
     * 落本地就等于把会话钉死在这一台机器上，请求飘到别的实例就会「列表有、点进去 404」，
     * 多副本下这是必然发生的，不是偶发。
     */
    @Bean
    public ChatSessionRegistry chatSessionRegistry(AgentServiceProperties properties) {
        return new ChatSessionRegistry(properties.getReplayBufferSize(), properties.getSessionCacheSize());
    }

    /** 会话日志（唯一事实源，§19.6）：先落日志再投影 SSE。 */
    @Bean(destroyMethod = "close")
    public AppendOnlyEventLog appendOnlyEventLog(AgentServiceProperties properties) {
        // 三个「会自己长大」的旋钮都来自配置：单文件上限、保留窗口、维护间隔（见 application.yml 那一段）。
        EventLogConfig config = new EventLogConfig(
                Path.of(properties.getEventLogDir()),
                instanceId(properties),
                properties.getEventLogMaxBytes(),
                EventLogConfig.DEFAULT_FSYNC_BATCH_SIZE,
                EventLogConfig.DEFAULT_FSYNC_INTERVAL_MS,
                properties.getEventLogRetention());
        return new AppendOnlyEventLog(config, new FileEventLogCursorStore(config.cursorFile()));
    }

    /**
     * 会话状态的落点（§19.5）：生产 PG、测试 H2，实现用框架扩展里的 JDBC 版。
     *
     * <p>**为什么没有「落本地文件」这个选项**：状态存在服务器的本地磁盘上，就等于把会话钉死在这一台机器上——
     * 换实例就找不到会话（§2.3 隐性单点）。多副本是既定目标，所以这里不提供一个会把正确性悄悄降级的开关：
     * 单机开发也照样连 PG（本地起一个），跑的就是生产那条路径。
     *
     * <p>**为什么用框架的实现，而不是自己写一个**：版本 CAS（{@code saveIfVersion}）是接口上的
     * **默认方法**，默认实现不看版本、直接覆盖；而框架的 JDBC 扩展把它覆写成了正确的事务内 CAS
     * ——单条 {@code UPDATE ... WHERE version = ?} 判断影响行数，插入用 {@code ON CONFLICT DO NOTHING}。
     * 自己再实现一遍属于重复造轮子（台账 T1-13）。
     *
     * <p>**为什么要包一层**：框架那个实现漏了「按 key 删除」（接口上的默认实现是空方法），
     * 平台侧「清掉一段状态」的调用会静默失效。{@link PlatformAgentStateStore} 只补这一处，
     * 其余一律委托——细节与耦合点的说明都在那个类的注释里。
     *
     * <p>**方言自动识别**：方言按 DataSource 认（生产 PG、测试 H2），所以 CAS 语义在生产与测试里
     * 是同一套代码，不存在「测试跑一套、生产跑另一套」。
     *
     * <p>**表由迁移脚本建，不由应用建**：表不存在就让启动失败（见
     * {@code deploy/migrations/V16__agent_state_framework_store.sql}）。
     *
     * <p>{@code @DependsOnDatabaseInitialization} 是这条取舍的配套：这个 store 一造出来就要确认表在，
     * 所以它必须排在「建表脚本跑完」之后。测试用 H2 时脚本是 {@code schema-h2.sql}（由 Spring 的
     * {@code spring.sql.init} 执行），生产是迁移脚本——两种情况下这个注解都保证顺序。
     */
    @Bean
    @DependsOnDatabaseInitialization
    public io.agentscope.core.state.AgentStateStore agentStateStore(DataSource dataSource) {
        return PlatformAgentStateStore.create(dataSource);
    }

    /** 平台轮次快照（HITL 挂起 / 恢复）在会话状态库里的读写口（§19.5），键固定为 {@code platform_turn}。 */
    @Bean
    public PlatformTurnStore platformTurnStore(io.agentscope.core.state.AgentStateStore agentStateStore) {
        return new PlatformTurnStore(agentStateStore);
    }

    /** 会话档案（建档 / 列表 / 归档）的读写口，键 {@code platform_session}（与会话正文同一张表）。 */
    @Bean
    public PlatformSessionStore platformSessionStore(io.agentscope.core.state.AgentStateStore agentStateStore) {
        return new PlatformSessionStore(agentStateStore);
    }

    /**
     * 「当前这一轮」的开始标记（T1-08）：开跑前写、跑完删，用来解释「实例挂了，这一轮没跑完」。
     *
     * <p>与挂起快照同表不同键：它每条轮次都重写一次，是三个平台侧键里唯一高频的一个。
     */
    @Bean
    public PlatformLiveTurnStore platformLiveTurnStore(io.agentscope.core.state.AgentStateStore agentStateStore) {
        return new PlatformLiveTurnStore(agentStateStore);
    }

    /**
     * 会话目录（§19.4）：建会话、会话列表、历史、归档——全部**现读会话状态现算**。
     *
     * <p>它是「历史不再自建一套台账」的落点：从前那套 journal 既是回放位又是列表来源，
     * 于是同一件事有两个真相；现在正文只有框架状态一份，这里只负责把它翻译成前端认的形状。
     */
    @Bean
    public SessionCatalog sessionCatalog(
            io.agentscope.core.state.AgentStateStore agentStateStore,
            PlatformSessionStore platformSessionStore,
            PlatformLiveTurnStore platformLiveTurnStore) {
        return new SessionCatalog(agentStateStore, platformSessionStore, platformLiveTurnStore);
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
     * <p>状态存储用平台自己的 PG 实现（{@link JdbcAgentStateStore}）：会话状态不再跟进程走，
     * 换实例 / 换 pod / 实例挂掉之后，会话都还在（§19.5）。
     */
    @Bean(destroyMethod = "closeAll")
    @DependsOnDatabaseInitialization
    public AgentRuntimePort agentscopeRuntime(
            JdbcLlmProviderStore store,
            AgentServiceProperties properties,
            io.agentscope.core.state.AgentStateStore agentStateStore,
            DataSource dataSource,
            ObjectStorage objectStorage) {
        com.djzy.assistant.runtime.agentscope.ModelProvider modelProvider = new ConfiguredModelProvider(
                store, () -> "raw_reasoning".equalsIgnoreCase(properties.getThinkingVisibility()));
        return new com.djzy.assistant.runtime.agentscope.AgentscopeRuntimeAdapter(
                modelProvider,
                agentStateStore,
                // HarnessAgent 的工作目录：默认按 cwd 建 .agentscope/，会把会话原文写进应用启动目录。
                // 钉到工作目录下，运行时自己的痕迹（若有）只落在平台管得到的地方。
                java.nio.file.Path.of(properties.getWorkspaceDir(), "agentscope-workspace"),
                workspaceStore(properties, dataSource),
                skillProvisioner(properties, dataSource, objectStorage),
                sandboxSettings(properties, dataSource));
    }

    /**
     * 工作区存储（H-04）：agent 的文件空间放哪儿——本机磁盘（返回 {@code null}）还是共用的库。
     *
     * <p>做成一个独立的小方法而不是在 bean 里写 if/else：{@code agentscopeRuntime} 那一段是「装配运行时」，
     * 混进配置校验会看不清主线；而且取值校验只有一处，配错了当场启动失败。
     *
     * <p>{@code @DependsOnDatabaseInitialization} 是这一项带来的新顺序要求：{@code jdbc} 时会先去确认
     * 表 {@code agentscope_store} 在不在（{@code PlatformWorkspaceStore}），所以必须排在建表脚本之后。
     */
    private static io.agentscope.harness.agent.filesystem.remote.store.BaseStore workspaceStore(
            AgentServiceProperties properties, DataSource dataSource) {
        String where = properties.getWorkspaceStore();
        if ("none".equalsIgnoreCase(where)) {
            log.info("工作区存储：本机磁盘（agent-service.workspace-store=none；多副本下各实例的工作区互不可见）");
            return null;
        }
        if ("jdbc".equalsIgnoreCase(where)) {
            log.info("工作区存储：共享库（agent-service.workspace-store=jdbc，表 agentscope_store；多副本看到同一份）");
            return PlatformWorkspaceStore.create(dataSource);
        }
        throw new IllegalStateException(
                "agent-service.workspace-store 取值不认识：" + where + "（只支持 none / jdbc）");
    }

    /**
     * 技能下发（H-06a / DR-41）：把「这个用户可见的技能」的内容同步进他的工作区。
     *
     * <p>与 {@code workspaceStore} 一样做成独立小方法：取值校验只在一处，配错了当场启动失败；
     * 关着的时候返回 {@code NONE}——运行时连「技能发现」都不打开（见 {@code AgentscopeRuntimeAdapter}）。
     *
     * <p>为什么要 {@code ObjectStorage}：技能「算数的是哪一版」由管理端发布时写的那张表说了算
     * （{@code sys_skill_version}，agent 侧只读），内容本身在对象存储里。
     */
    private static com.djzy.assistant.runtime.agentscope.SkillProvisioner skillProvisioner(
            AgentServiceProperties properties, DataSource dataSource, ObjectStorage objectStorage) {
        String mode = properties.getWorkspaceSkills();
        if ("none".equalsIgnoreCase(mode)) {
            log.info("技能下发：关闭（agent-service.workspace-skills=none；模型看不到任何技能）");
            return com.djzy.assistant.runtime.agentscope.SkillProvisioner.NONE;
        }
        if ("workspace".equalsIgnoreCase(mode)) {
            // 沙箱开着时技能会被投影进容器、脚本在容器里跑（H-06b）：技能正文里那句「能不能跑脚本」跟着沙箱走。
            boolean scriptExecutionAvailable =
                    properties.getSandbox() != null && properties.getSandbox().isEnabled();
            log.info("技能下发：开（agent-service.workspace-skills=workspace；技能写进用户工作区，按人隔离；脚本{}）",
                    scriptExecutionAvailable ? "在容器里可执行" : "当前不执行");
            SkillPackageSource source = new RegistrySkillPackageSource(dataSource, objectStorage);
            return new WorkspaceSkillProvisioner(source, scriptExecutionAvailable);
        }
        throw new IllegalStateException(
                "agent-service.workspace-skills 取值不认识：" + mode + "（只支持 none / workspace）");
    }

    /**
     * 沙箱设置（H-05）：命令要不要放到容器里跑。
     *
     * <p>与上面两个一样做成独立小方法：翻译与打印只在**一处**，配错了当场看得到。
     * 取值校验（0 = 不限制）也在这里做——{@link SandboxSettings} 只管"是什么"，不管"从哪来"。
     *
     * <p>「Docker 到底在不在」不在这里判：那是启动期自检的事（{@code StartupSandboxCheck}），
     * 因为那件事跟"装配运行时"没关系，是**这台机器是否具备条件**的问题。
     */
    private static com.djzy.assistant.runtime.agentscope.SandboxSettings sandboxSettings(
            AgentServiceProperties properties, DataSource dataSource) {
        AgentServiceProperties.Sandbox sandbox = properties.getSandbox();
        var settings = new com.djzy.assistant.runtime.agentscope.SandboxSettings(
                sandbox.isEnabled(),
                sandbox.getImage(),
                sandbox.getWorkspaceRoot(),
                // 0 = 不限制（配置里用 0 而不是留空：留空会让数字类型的绑定直接报错）
                sandbox.getMemoryBytes() > 0 ? sandbox.getMemoryBytes() : null,
                sandbox.getCpuCount() > 0 ? sandbox.getCpuCount() : null,
                sandbox.getNetwork(),
                sandbox.getSharedPrefixes(),
                sandboxExecutionGuard(sandbox, dataSource));
        log.info("沙箱：{}", settings.describe());
        return settings;
    }

    /**
     * 沙箱的跨实例执行锁（H-11）：同一个用户同时只允许一轮真的在容器里跑。
     *
     * <p>为什么默认就打开：沙箱的隔离槽是<b>按用户</b>分的，同一个用户的两轮并发开始时会各起一个容器、
     * 后写的状态覆盖先写的（L-21 有实测记录）。这属于「不报错但会悄悄坏」的一类，所以它跟着沙箱开关走：
     * 开了沙箱就默认上锁，不想上（比如单实例试跑）可以 {@code agent-service.sandbox.distributed-lock=false} 关掉。
     *
     * <p>锁的实现来自框架扩展（{@code JdbcSandboxExecutionGuard}），平台只负责把它接上；
     * 锁的后端跟着 DataSource 的方言走（生产 PG = advisory lock，进程挂了会自动松；测试 H2 = 一张锁表）。
     * 开关与超时的取值校验只在这一处。
     */
    private static io.agentscope.harness.agent.sandbox.SandboxExecutionGuard sandboxExecutionGuard(
            AgentServiceProperties.Sandbox sandbox, DataSource dataSource) {
        if (!sandbox.isEnabled() || !sandbox.isDistributedLock()) {
            return null;
        }
        log.info("沙箱执行锁：开（等锁上限 {}；同一用户同时只跑一轮，抢不到的那轮等超时后失败）", sandbox.getLockTimeout());
        return PlatformSandboxExecutionGuard.create(dataSource, sandbox.getLockTimeout());
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

    /**
     * 跨副本实时续看用的共享总线（H-01）：框架 {@code MessageBus} 的 Redis 实现。
     *
     * <p>为什么是我们实现框架的接口、而不是用框架自带的实现：框架 2.0.3 只带了一个
     * {@code WorkspaceMessageBus}，它把消息写进「工作区文件系统」，服务的是 agent **内部**的
     * 收件箱 / 异步工具 / 子代理——那些能力平台一律关闭（ADR-31 基线）。接入层要的是「会话时间线」
     * 那组语义（{@code sessionPublishEvent / sessionReadEvents}），所以这里保留框架的接口与默认方法，
     * 只把底座换成 Redis（细节与理由都在 RedisMessageBus 的类注释里）。
     *
     * <p>键前缀带上 {@code agent-service}：与事件队列、轮次闸门同一条纪律——同一个 Redis 上
     * 可能有别的系统在跑，键必须自带归属。
     *
     * <p>日志 TTL 两小时：它只是「这一轮正在跑」的临时数据（会话正文的权威始终是 PG 里的框架状态），
     * 跑完一段时间后自己消失，不需要任何清理任务。
     */
    @Bean(destroyMethod = "close")
    public MessageBus liveMessageBus(
            AgentServiceProperties properties,
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<RedisConnectionFactory> connectionProvider) {
        if ("redis".equalsIgnoreCase(properties.getLiveBus())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            RedisConnectionFactory connections = connectionProvider.getIfAvailable();
            if (template == null || connections == null) {
                throw new IllegalStateException("agent-service.live-bus=redis 但容器中没有 Redis 连接");
            }
            // 启动时把「这台实例用的是哪条总线」写一行日志：多副本排查「跨实例看不到实时内容」时，
            // 第一件事就是确认两台机器都装上了同一条 Redis 总线，而不是偷偷退回了单机内存实现。
            log.info("跨副本实时总线：Redis（前缀 agent-service:bus:，日志 TTL 2 小时）");
            return new RedisMessageBus(template, connections, "agent-service:bus:", Duration.ofHours(2));
        }
        log.info("跨副本实时总线：进程内（agent-service.live-bus=memory；多副本下跨实例实时不成立）");
        return new InMemoryMessageBus();
    }

    /**
     * 「这一轮正在产出什么」的跨副本共享口（H-01）：写与读都在这里，调用方（ChatService /
     * CrossInstanceTurnRelay）只跟它打交道，不直接碰框架的总线接口。
     *
     * <p>每次读的上限 200 条：日志本身有上限（框架每条会话事件给的是 1000 条），一次读太多
     * 也没有意义——追实时是一批一批往前赶的，读到多少、游标就走到哪。
     */
    @Bean
    public LiveTurnChannel liveTurnChannel(MessageBus liveMessageBus) {
        return new LiveTurnChannel(liveMessageBus, 200);
    }

    /**
     * 跨实例接流（T1-09 / H-01）：本实例没有这一轮时，追着共享总线把别台机器正在产出的内容实时下发；
     * 总线读不到东西（单实例 / 老数据）就退回「等跑完再整段补」，三种结局都给出交代。
     *
     * <p>等待窗口取「SSE 超时 - 5 秒」：必须比连接本身的超时短一点，否则用户先看到的是一条
     * 被平台掐断的连接，而不是我们准备好的那句解释。超时配得特别短时（测试）就让窗口等于它，
     * 免得配出一个负数的窗口。
     */
    @Bean
    public CrossInstanceTurnRelay crossInstanceTurnRelay(
            SessionCatalog sessionCatalog,
            SessionTurnGate sessionTurnGate,
            LiveTurnChannel liveTurnChannel,
            AgentServiceProperties properties) {
        Duration streamTimeout = properties.getStreamTimeout();
        Duration waitBudget = streamTimeout.minusSeconds(5);
        if (waitBudget.isNegative() || waitBudget.isZero()) {
            waitBudget = streamTimeout;
        }
        return new CrossInstanceTurnRelay(
                sessionCatalog,
                sessionTurnGate,
                liveTurnChannel,
                // 追实时的节奏（默认 200 毫秒）：见 AgentServiceProperties#liveBusPollInterval
                properties.getLiveBusPollInterval(),
                // 看共享会话状态的节奏（1 秒）：它比追实时贵得多（要读一遍这一轮的投影），
                // 而「跑完了 / 没人管了 / 超时了」这三种结局本来就不需要毫秒级粒度
                Duration.ofSeconds(1),
                waitBudget,
                properties.turnLease());
    }

    @Bean
    public ChatService chatService(
            ChatSessionRegistry registry,
            SessionCatalog sessionCatalog,
            EntryTicketService tickets,
            RuntimeRegistry runtimes,
            ToolPlane toolPlane,
            PlatformTurnStore turnStore,
            SseProjector projector,
            TurnLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Qualifier("eventPublisher") EventPublisher events,
            TurnStopSignalStore stopSignals,
            TurnStopChannel stopChannel,
            SessionTurnGate sessionTurnGate,
            PlatformLiveTurnStore platformLiveTurnStore,
            CrossInstanceTurnRelay crossInstanceTurnRelay,
            LiveTurnChannel liveTurnChannel,
            AgentServiceProperties properties) {
        return new ChatService(
                registry,
                sessionCatalog,
                tickets,
                runtimes,
                toolPlane,
                turnStore,
                projector,
                rateLimiter,
                events,
                stopSignals,
                stopChannel,
                sessionTurnGate,
                platformLiveTurnStore,
                crossInstanceTurnRelay,
                liveTurnChannel,
                // 实例名写进轮次坑位与开始标记：排障时一眼看出「这一轮是哪台机器在跑」
                instanceId(properties),
                properties);
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
