package com.djzy.assistant.agentweb.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** agent-service 接入层配置（§12.2 / §19.4 / §20.1.5）。 */
@ConfigurationProperties(prefix = "agent-service")
public class AgentServiceProperties {

    /** 密钥表：keyId → shared_secret（登录令牌校验密钥、调用网关的服务密钥，部署期注入）。 */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /** 登录令牌（JWT）**校验用**密钥的 keyId；必须与签发方 management-service 一致（§20.1.1）。 */
    private String tokenKeyId = "login-token";

    /** 令牌有效期配置只为构造校验器；校验不看 TTL，这里给一个合法值即可（≤15 分钟，§19.4）。 */
    private Duration tokenTtl = Duration.ofMinutes(15);

    /** 运行时 id（{@code agentscope} / {@code noop}）；未通过 TCK 的实现在运行时注册表里会被回退（§18.11.6）。 */
    private String runtimeId = "noop";

    /** 已通过 TCK 的运行时白名单（§18.11.6：新实现必须先跑通 TCK 才允许上线）。 */
    private java.util.List<String> tckPassedRuntimes = new java.util.ArrayList<>(java.util.List.of("noop"));

    /** 单轮最大推理步数。 */
    private int maxIters = 10;

    /** 入场券有效期（§19.4：一次性、短时有效）。 */
    private Duration ticketTtl = Duration.ofSeconds(60);

    /** 单轮预算（§9.1 超时预算链的 agent 侧一段）。 */
    private Duration turnDeadline = Duration.ofMinutes(5);

    /** SSE 连接最长存活时间（§20.11 未实现部分之外的兜底，到点收尾并提示重连）。 */
    private Duration streamTimeout = Duration.ofMinutes(10);

    /** 单用户每分钟可发起的轮次数（§9.3 用户限流）。 */
    private int turnsPerMinute = 30;

    /** 入场券存储：{@code memory}（单实例）或 {@code redis}（多副本必须用，§20.1.4 同款理由）。 */
    private String ticketStore = "memory";

    /** 限流计数存储：{@code memory}（单实例）或 {@code redis}（多副本必须用，§2.3 禁止本地内存计数）。 */
    private String rateLimitStore = "memory";

    /** 调用网关的凭据 keyId（网关 {@code secrets} 里必须有这一项，§20.1.2）。 */
    private String gatewayKeyId = "agent-service";

    /** 网关基地址；为空表示骨架期不接数据面（工具清单为空，运行时调不到任何工具）。 */
    private String gatewayUrl = "";

    /** 会话日志（唯一事实源）根目录，必须挂真实卷（§19.6 / ADR-28）。 */
    private String eventLogDir = "./data/eventlog";

    /** 运行时状态（挂起/恢复）根目录；骨架期落本地卷，接 PG 后换实现（§19.5）。 */
    private String stateDir = "./data/agent-state";

    /**
     * 运行时状态落点（§19.5）：{@code pg}（默认，多副本 / 换 pod / 断线续传都靠它）或
     * {@code file}（本地卷 JSON，仅限单副本骨架场景，此时 {@link #stateDir} 生效）。
     */
    private String stateStore = "pg";

    /** 实例标识（日志文件名用）；留空则由启动时生成（主机名 + 随机后缀）。 */
    private String instanceId = "";

    /** 每会话回放缓冲条数（断线续传的热缓存上限）。 */
    private int replayBufferSize = 4096;

    /** 思考流可见性（§20.6）：{@code step_card_only}（默认）或 {@code raw_reasoning}（用户个人设置项）。 */
    private String thinkingVisibility = "step_card_only";

    /**
     * 落库通道（§19.6）：{@code pg-outbox}（默认，事件入队 {@code event_outbox} 后由 event-persist 消费者
     * 攒批写事实表）、{@code redis-stream}（{@code XADD} / {@code XREADGROUP} / {@code XPENDING}）
     * 或 {@code none}（只落本地 append-only 日志）。
     */
    private String eventBus = "pg-outbox";

    /**
     * Redis Stream 名（{@code event-bus=redis-stream} 时才用）。
     *
     * <p>多环境共用一个 Redis 时必须各用各的流名：混流不会丢事件，但会让一个环境的事实表吃到另一个环境的数据。
     */
    private String eventStream = "doctor:events";

    private boolean requireSecrets = true;

    /**
     * 模型 API Key 的解密根密钥（Base64 编码的 32 字节，§20.1.6）。
     *
     * <p>必须与 management-service 的 {@code MANAGEMENT_LLM_KEK} 是同一把——密文是管理端写的，
     * 这里只负责解。留空不阻塞启动（noop 运行时用不到模型），
     * 但运行时要真的调模型时会**明确失败**，而不是拿一个空 Key 去撞 401。
     */
    private String llmKek = "";

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets == null ? new LinkedHashMap<>() : secrets;
    }

    public String getTokenKeyId() {
        return tokenKeyId;
    }

    public void setTokenKeyId(String tokenKeyId) {
        this.tokenKeyId = tokenKeyId;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    public java.util.List<String> getTckPassedRuntimes() {
        return tckPassedRuntimes;
    }

    public void setTckPassedRuntimes(java.util.List<String> tckPassedRuntimes) {
        this.tckPassedRuntimes =
                tckPassedRuntimes == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(tckPassedRuntimes);
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public Duration getTicketTtl() {
        return ticketTtl;
    }

    public void setTicketTtl(Duration ticketTtl) {
        this.ticketTtl = ticketTtl;
    }

    public Duration getTurnDeadline() {
        return turnDeadline;
    }

    public void setTurnDeadline(Duration turnDeadline) {
        this.turnDeadline = turnDeadline;
    }

    public Duration getStreamTimeout() {
        return streamTimeout;
    }

    public void setStreamTimeout(Duration streamTimeout) {
        this.streamTimeout = streamTimeout;
    }

    public int getTurnsPerMinute() {
        return turnsPerMinute;
    }

    public void setTurnsPerMinute(int turnsPerMinute) {
        this.turnsPerMinute = turnsPerMinute;
    }

    public String getTicketStore() {
        return ticketStore;
    }

    public void setTicketStore(String ticketStore) {
        this.ticketStore = ticketStore;
    }

    public String getRateLimitStore() {
        return rateLimitStore;
    }

    public void setRateLimitStore(String rateLimitStore) {
        this.rateLimitStore = rateLimitStore;
    }

    public String getGatewayKeyId() {
        return gatewayKeyId;
    }

    public void setGatewayKeyId(String gatewayKeyId) {
        this.gatewayKeyId = gatewayKeyId;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl == null ? "" : gatewayUrl;
    }

    public String getEventLogDir() {
        return eventLogDir;
    }

    public void setEventLogDir(String eventLogDir) {
        this.eventLogDir = eventLogDir;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }

    public String getStateStore() {
        return stateStore;
    }

    public void setStateStore(String stateStore) {
        this.stateStore = stateStore == null ? "pg" : stateStore;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId == null ? "" : instanceId;
    }

    public int getReplayBufferSize() {
        return replayBufferSize;
    }

    public void setReplayBufferSize(int replayBufferSize) {
        this.replayBufferSize = replayBufferSize;
    }

    public String getThinkingVisibility() {
        return thinkingVisibility;
    }

    public String getEventBus() {
        return eventBus;
    }

    public void setEventBus(String eventBus) {
        this.eventBus = eventBus == null ? "pg-outbox" : eventBus;
    }
    public String getEventStream() {
        return eventStream;
    }

    public void setEventStream(String eventStream) {
        this.eventStream = eventStream == null || eventStream.isBlank() ? "doctor:events" : eventStream;
    }

    public void setThinkingVisibility(String thinkingVisibility) {
        this.thinkingVisibility = thinkingVisibility == null ? "step_card_only" : thinkingVisibility;
    }

    public boolean isRequireSecrets() {
        return requireSecrets;
    }

    public void setRequireSecrets(boolean requireSecrets) {
        this.requireSecrets = requireSecrets;
    }

    public String getLlmKek() {
        return llmKek;
    }

    public void setLlmKek(String llmKek) {
        this.llmKek = llmKek;
    }

    public boolean gatewayConfigured() {
        return gatewayUrl != null && !gatewayUrl.isBlank();
    }
}
