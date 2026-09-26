package com.djzy.assistant.agentweb.config;

import com.djzy.assistant.common.eventlog.EventLogConfig;
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

    /**
     * 入场券存储：{@code redis}（默认，多副本必须用，§20.1.4 同款理由——单实例内存券
     * 无法保证跨实例一次性）或 {@code memory}（仅本机开发 / 单副本）。
     */
    private String ticketStore = "redis";

    /**
     * 限流计数存储：{@code redis}（默认，多副本必须用，§2.3 禁止本地内存计数——各数一份
     * 会让实际额度放大成「副本数 × 配置值」）或 {@code memory}（仅本机开发 / 单副本）。
     */
    private String rateLimitStore = "redis";

    /**
     * 停止信号存储：{@code redis}（默认）或 {@code memory}（仅本机开发 / 单副本）。
     *
     * <p>多副本必须用 {@code redis}：否则「停止」只能停掉**恰好落在同一个实例上**的那一轮，
     * 打到别的实例就静默失效——用户以为停了，模型还在跑。
     *
     * <p>这一项是**兜底**那条路（带 TTL 的键，谁在跑这一轮就在下一个流事件上看到它）。
     * 更快的那条「推送」跟着 {@code live-bus} 走，见 {@code TurnStopChannel}——它丢了也不会停不下来，
     * 只会慢一点，所以两件事的开关互不牵连。
     */
    private String stopSignalStore = "redis";

    /**
     * 轮次闸门的存储：{@code redis}（默认）或 {@code memory}（仅本机开发 / 单副本）。
     *
     * <p>它管的是「同一个会话同时只能跑一轮」。多副本必须用 {@code redis}：
     * 用内存版的话，两次请求分别落到两台实例上时，两边都以为「没人跑」，
     * 于是同一个会话被两轮并发写入（T1-07）。
     */
    private String turnGateStore = "redis";

    /**
     * 跨副本实时续看用的共享总线（H-01）：{@code redis}（默认，多副本必须用）或 {@code memory}（仅本机开发 / 单副本）。
     *
     * <p>它管的是「这一轮正在产出什么」：跑这一轮的实例每下发一条事件就往总线上记一条，
     * 重连落到别的实例时，那台实例按游标追着读，于是回答**边跑边到**，而不是等整轮跑完一次性出现。
     *
     * <p>用 {@code memory} 只会退化成改造前的样子（等整轮跑完再整段补），**不会出错**：
     * 内存版天然只在**本进程**里可见，别的实例上那一轮在这里一条也读不到。
     */
    private String liveBus = "redis";

    /**
     * 追实时的间隔：隔多久去总线上取一批这一轮的新事件。
     *
     * <p>为什么不做成「订阅推送」：日志天然有序、不重不漏，而推送在断线重连的交界处必然要么丢一段、
     * 要么重一段（见 LiveTurnChannel 的说明）。模型本来就是「一个字一个字吐」的，
     * 一个间隔一批读回来，用户感知不到差别——这是拿一点点延迟换掉一整层去重与排序。
     */
    private Duration liveBusPollInterval = Duration.ofMillis(200);

    /** 调用网关的凭据 keyId（网关 {@code secrets} 里必须有这一项，§20.1.2）。 */
    private String gatewayKeyId = "agent-service";

    /** 网关基地址；为空表示骨架期不接数据面（工具清单为空，运行时调不到任何工具）。 */
    private String gatewayUrl = "";

    /** 会话日志（唯一事实源）根目录，必须挂真实卷（§19.6 / ADR-28）。 */
    private String eventLogDir = "./data/eventlog";

    /**
     * 会话日志的单文件上限（字节）：文件到这个大小就滚动（旧文件按时间戳留档，见 §19.6 / ADR-28）。
     *
     * <p>为什么必须有：日志是按实例写的单文件，一个实例连着跑，它就是一直加长的那一个文件。
     * 默认 256 MB 是「出问题时好打开看、又不至于半天就滚一堆」的折中。
     */
    private long eventLogMaxBytes = EventLogConfig.DEFAULT_MAX_FILE_BYTES;

    /**
     * 会话日志的保留窗口：**已确认投递**且比它更旧的记录可以裁掉（默认 7 天）。
     *
     * <p>注意裁的是「已确认投递」的那部分：还没送进队列的记录，多旧都不能删——删了就是丢事件。
     */
    private Duration eventLogRetention = EventLogConfig.DEFAULT_RETENTION;

    /**
     * 运行时的工作目录（§19.5）：HarnessAgent 需要一个目录放它自己的临时痕迹。
     *
     * <p>**会话状态不在这里**：会话状态已经全部落 PG（见 {@code AgentServiceConfig#agentStateStore}），
     * 放在本地磁盘上的只有「这份部署自己的运行痕迹」，换个实例可以重建，丢了不影响会话。
     * 默认按 cwd 建 {@code .agentscope/} 是框架的行为，会把会话原文写进应用启动目录，所以这里必须钉住。
     */
    private String workspaceDir = "./data/agent-workspace";

    /**
     * 工作区（agent 的文件空间：技能 SKILL.md 与配套脚本、被写下的中间文件）落在哪里。
     *
     * <p>取值只有两个：
     * <ul>
     *   <li>{@code none}（默认）—— 只在这台实例的本地磁盘上。单副本够用，多副本时各是各的；</li>
     *   <li>{@code jdbc} —— 放共用的库（表 {@code agentscope_store}，见 {@code PlatformWorkspaceStore}），
     *       多副本看到同一份：任意实例写、任意实例读。</li>
     * </ul>
     *
     * <p>为什么默认 {@code none}：工作区平面目前是关着的（技能、子代理、工作区上下文都没开，DR-12），
     * 现在打开它只会多一次库往返，没有任何东西会用到。等技能下发（H-06）真的用上工作区时，
     * 多副本部署把这一项改成 {@code jdbc} 即可，代码不用动。
     *
     * <p>配成这两个之外的值会**直接启动失败**：配错了要当场知道，不能悄悄按默认跑。
     */
    private String workspaceStore = "none";

    /**
     * 技能下发开关（H-06a / DR-41）：技能内容放哪儿。
     *
     * <ul>
     *   <li>{@code none}（默认）—— 不下发、也不让框架发现工作区里的技能：模型完全看不到技能，
     *       与改造前行为一致；</li>
     *   <li>{@code workspace} —— 每一轮把「这个用户可见的技能」的最新已发布版本同步进他的工作区
     *       （{@code skills/} 目录），框架自带的工作区技能仓库据此把它们列给模型。</li>
     * </ul>
     *
     * <p>开了它通常也要把 {@code workspace-store} 设成 {@code jdbc}：技能内容写在**工作区**里，
     * 工作区不共享的话，多副本下「哪台机器下发过、哪台没下发过」就全看运气。
     *
     * <p>为什么默认关：它要读对象存储、每轮多一次网关调用与一次库查询，还得有人真的上传并发布技能
     * 才看得出效果；关着的时候一条代价都不产生。
     */
    private String workspaceSkills = "none";

    /** 实例标识（日志文件名用）；留空则由启动时生成（主机名 + 随机后缀）。 */
    private String instanceId = "";

    /** 每个会话在内存里留多少条最近事件（只服务「这一轮还没跑完就断线」的重连，跑完的轮次走历史）。 */
    private int replayBufferSize = 4096;

    /**
     * 内存里最多同时留多少个会话句柄。
     *
     * <p>会话句柄是**纯内存**的（当前运行时句柄 + 回放缓冲），不设上限就会随「聊过的会话数」一直涨。
     * 超出后先淘汰「没在跑、也最久没人碰」的那些：正在跑的一轮是禁区（用户还要点停止 / 确认）。
     * 被淘汰的会话下次用到时重建一个空壳即可，历史仍从状态库读，不会有任何丢失。
     */
    private int sessionCacheSize = 512;

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

    /**
     * 轮次坑位的存活时长（T1-07）。
     *
     * <p>它**不是**「一轮能跑多久」，而是「进程没了之后，这个会话要等多久才能再发起一轮」。
     * 所以取「流的超时上限 + 5 分钟」：正常轮次结束时我们**显式放坑**，用不到这个值；
     * 它只在实例被硬杀（没人来放坑）时兜底——太长会让用户干等，太短会在真正还在跑的时候被人抢走。
     *
     * <p>公式收在配置类里，是因为有两个地方要用它：抢坑位的人（{@code ChatService}）和
     * 判断「这一轮是不是已经没人管了」的人（{@code CrossInstanceTurnRelay}）。
     * 两处各写一遍迟早会改歪一个，然后表现成「明明还在跑，却被当成中断」。
     */
    public Duration turnLease() {
        return streamTimeout.plusMinutes(5);
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
        this.ticketStore = ticketStore == null ? "redis" : ticketStore;
    }

    public String getStopSignalStore() {
        return stopSignalStore;
    }

    public void setStopSignalStore(String stopSignalStore) {
        this.stopSignalStore = stopSignalStore == null ? "redis" : stopSignalStore;
    }

    public String getLiveBus() {
        return liveBus;
    }

    public void setLiveBus(String liveBus) {
        this.liveBus = liveBus == null ? "redis" : liveBus;
    }

    public Duration getLiveBusPollInterval() {
        return liveBusPollInterval;
    }

    public void setLiveBusPollInterval(Duration liveBusPollInterval) {
        this.liveBusPollInterval = liveBusPollInterval;
    }

    public String getTurnGateStore() {
        return turnGateStore;
    }

    public void setTurnGateStore(String turnGateStore) {
        this.turnGateStore = turnGateStore;
    }

    public String getRateLimitStore() {
        return rateLimitStore;
    }

    public void setRateLimitStore(String rateLimitStore) {
        this.rateLimitStore = rateLimitStore == null ? "redis" : rateLimitStore;
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

    public long getEventLogMaxBytes() {
        return eventLogMaxBytes;
    }

    public void setEventLogMaxBytes(long eventLogMaxBytes) {
        this.eventLogMaxBytes = eventLogMaxBytes;
    }

    public Duration getEventLogRetention() {
        return eventLogRetention;
    }

    public void setEventLogRetention(Duration eventLogRetention) {
        this.eventLogRetention = eventLogRetention;
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }

    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    public String getWorkspaceStore() {
        return workspaceStore;
    }

    public void setWorkspaceStore(String workspaceStore) {
        this.workspaceStore = workspaceStore == null ? "none" : workspaceStore;
    }

    public String getWorkspaceSkills() {
        return workspaceSkills;
    }

    public void setWorkspaceSkills(String workspaceSkills) {
        this.workspaceSkills = workspaceSkills == null ? "none" : workspaceSkills;
    }

    /** 技能下发是否开着（{@code workspace-skills=workspace}）。 */
    public boolean isWorkspaceSkillsEnabled() {
        return "workspace".equalsIgnoreCase(workspaceSkills);
    }

    /** 沙箱（H-05）：技能脚本要不要放到容器里跑。默认关，字段与理由见 {@link Sandbox}。 */
    private Sandbox sandbox = new Sandbox();

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox == null ? new Sandbox() : sandbox;
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

    public int getSessionCacheSize() {
        return sessionCacheSize;
    }

    public void setSessionCacheSize(int sessionCacheSize) {
        this.sessionCacheSize = sessionCacheSize;
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
    /**
     * 沙箱设置（H-05）：**命令在哪跑**。
     *
     * <p><b>默认关着</b>：关着时没有任何执行面——没有容器、没有壳工具，模型能用的只有平台注册的工具
     * （ADR-31 基线）。这是有意的默认值：执行用户脚本需要额外的运行环境（Docker）与资源，
     * 不该在"加了依赖"的时候悄悄打开，见台账 DR-43。
     *
     * <p><b>打开后会发生三件事</b>：① 容器变成 agent 的主文件系统，命令在容器里跑，碰不到宿主机；
     * ② {@code shared-prefixes} 里那些路径仍然读写共享库（PG），多副本看到同一份；
     * ③ 沙箱里的壳工具被放行——这是开关打开后唯一故意偏离 ADR-31 基线的地方。
     *
     * <p><b>打开前必须确认</b>：本机（或运行环境）装好了 Docker 且能连上——启动时会自检，
     * 拿不到 {@code docker version} 就直接拒绝启动。**不会**在 Docker 不可用时偷偷退回本机执行：
     * "以为隔离了、其实没隔离"比启动失败危险得多。
     */
    public static class Sandbox {

        /** 是否启用容器沙箱（默认 false = 与改造前行为一致）。 */
        private boolean enabled = false;

        /** 容器镜像；不存在时框架调 {@code docker run} 会自己拉。 */
        private String image = "ubuntu:22.04";

        /** 容器里的工作区根目录（框架默认 /workspace）。 */
        private String workspaceRoot = "/workspace";

        /** 单容器内存上限（字节）；**0 = 不限制**。 */
        private long memoryBytes = 0;

        /** 单容器 CPU 上限（核数）；**0 = 不限制**。 */
        private long cpuCount = 0;

        /** 容器接入的 Docker 网络名；留空 = Docker 默认。 */
        private String network = "";

        /**
         * 仍然读写共享库（PG）的工作区前缀：**目录边界写法、逐个枚举**，逗号分隔。
         *
         * <p>默认只有 {@code skills/}：它是目前唯一"平台写进工作区、且必须跨实例看到同一份"的东西。
         * 写成单个斜杠不会匹配任何路径（框架的路由按前缀逐条比对，见台账 L-15）。
         */
        private java.util.List<String> sharedPrefixes = new java.util.ArrayList<>(java.util.List.of("skills/"));

        /**
         * 沙箱开着时，是否上「跨实例执行锁」（H-11，默认 true）。
         *
         * <p>要防的是：同一个用户的两轮几乎同时开始（一台实例上的两个会话、或者两台实例各一轮），
         * 隔离槽是<b>按用户</b>分的，两边会各自新建一个容器，最后后写的覆盖先写的那个状态。
         * 锁的后端跟着数据库方言走：生产 PG 用 advisory lock（进程挂了自动松），
         * 测试 H2 退化成一张锁表。
         *
         * <p><b>打开它有两个代价</b>：后到的那一轮要等（等不到按超时失败，见 {@link #lockTimeout}）；
         * PG 那条路会占一条数据库连接，时长 = 这一轮沙箱活着的时间，所以并发跑脚本的用户数
         * 不能超过连接池大小。只想单实例试沙箱、不想动连接池时可以关掉它。
         */
        private boolean distributedLock = true;

        /** 等锁上限（默认 5 分钟）：等不到就让这一轮失败并报错，不无限挂着。只在 {@link #distributedLock} 打开时有用。 */
        private java.time.Duration lockTimeout = java.time.Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = (image == null || image.isBlank()) ? "ubuntu:22.04" : image;
        }

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = (workspaceRoot == null || workspaceRoot.isBlank()) ? "/workspace" : workspaceRoot;
        }

        public long getMemoryBytes() {
            return memoryBytes;
        }

        public void setMemoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
        }

        public long getCpuCount() {
            return cpuCount;
        }

        public void setCpuCount(long cpuCount) {
            this.cpuCount = cpuCount;
        }

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network == null ? "" : network;
        }

        public java.util.List<String> getSharedPrefixes() {
            return sharedPrefixes;
        }

        public void setSharedPrefixes(java.util.List<String> sharedPrefixes) {
            this.sharedPrefixes = (sharedPrefixes == null || sharedPrefixes.isEmpty())
                    ? new java.util.ArrayList<>(java.util.List.of("skills/"))
                    : new java.util.ArrayList<>(sharedPrefixes);
        }

        public boolean isDistributedLock() {
            return distributedLock;
        }

        public void setDistributedLock(boolean distributedLock) {
            this.distributedLock = distributedLock;
        }

        public java.time.Duration getLockTimeout() {
            return lockTimeout;
        }

        public void setLockTimeout(java.time.Duration lockTimeout) {
            this.lockTimeout = lockTimeout == null ? java.time.Duration.ofMinutes(5) : lockTimeout;
        }
    }
}
