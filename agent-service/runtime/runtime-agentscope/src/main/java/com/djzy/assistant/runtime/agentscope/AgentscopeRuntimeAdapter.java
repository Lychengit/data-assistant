package com.djzy.assistant.runtime.agentscope;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.PendingConfirmationException;
import com.djzy.assistant.spi.RuntimeCapabilities;
import com.djzy.assistant.spi.RuntimeMismatchException;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import com.djzy.assistant.spi.Snapshot;
import com.djzy.assistant.spi.tool.ToolSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 默认运行时实现（ADR-18 二次修订 / ADR-31 基线 / ADR-32）。
 *
 * <p>装配外壳用 {@link HarnessAgent}，内包 core {@code ReActAgent}；**ADR-31 基线**：
 * {@code disableFilesystemTools()} + {@code disableShellTool()} + {@code disableMemoryTools()}，
 * 运行时不执行任何工具（工具执行一律回到平台 {@code ToolInvoker}）。
 *
 * <p>危险默认值提醒（ADR-33 ①）：{@code HarnessAgent} 默认文件系统是 {@code LocalFilesystemWithShell}
 * （{@code sh -c} 直接跑在应用服务器上），因此本适配器**必须**显式关闭文件与命令能力，
 * 不得以默认配置对外提供脚本执行。要执行用户脚本时，唯一的正路是把它交给容器沙箱（H-05）：
 * {@code agent-service.sandbox.enabled=true} 打开后，命令的执行场所是容器，不是这台机器。
 *
 * <h2>多副本下这个类怎么工作（T1-06）</h2>
 *
 * <p><b>agent 是共享的，会话状态是分段的。</b> 这正是框架自己的用法：一个 agent 实例服务所有用户，
 * 每次调用开始时按 {@code RuntimeContext} 里的 {@code (userId, sessionId)} 把**这一段**的状态装进来、
 * 结束时写回状态库，同一段的并发调用由框架串行化。所以「换实例接着聊」不需要我们额外做什么：
 * 另一台机器上的同一个 agent 会从同一份状态库里把这个会话读出来（B 成立）。
 *
 * <p>共享的粒度是「**工具面 + 模型 + 迭代上限**」这一把键（见 {@link #agentKey}）：
 * 这三样在框架里是 agent 级字段，改不了，也不该改。而每轮都变的东西——系统提示词、平台回调、
 * 本轮 id 与截止时间——一律放进 {@code RuntimeContext} 按调用传递（见 {@link CallAttributes}）。
 *
 * <p>于是这里有一组**必须成对**的改动，少一个就会退化成「一台机器只能服务一个人」：
 * ① agent 不再 per-session 新建（{@link SharedAgentPool}）；
 * ② 工具对象不再持有发起者的回调（{@link PlatformToolAdapter} 从调用上下文取）；
 * ③ 系统提示词走中间件按轮注入（{@link PerCallSystemPrompt}），不烤进 agent。
 *
 * <p><b>工作区也能是多副本共享的</b>（H-04，默认关闭）：会话状态（聊了什么）落在状态库，工作区文件（技能与脚本）
 * 落在共享存储。两者是两块不同的东西——前者按 (userId, sessionId) 寻址、每轮一读一写；后者是 agent 的文件空间。
 * 开与不开由 {@code agent-service.workspace-store} 决定，见 {@link #workspaceStore}。
 *
 * <p><b>执行面是可选的</b>（H-05，默认关闭）：开了沙箱之后，容器变成 agent 的主文件系统（命令在容器里跑），
 * 而"必须跨实例看到同一份"的那些前缀继续走共享库（默认只有 {@code skills/}，见 {@link SandboxSharedRoutes}）。
 * 两件事分开的理由：容器负责**隔离**，共享库负责**一致**，谁都不兼任对方。
 *
 * <p><b>容器里的技能是"投影"进去的</b>（H-06b）：路由让模型**读**得到技能（读的是共享库那份），
 * 投影让容器**跑**得动技能里的脚本（每轮把共享库里的 {@code skills/} 抄到本机、再交给框架灌进容器，
 * 见 {@link #prepareSandbox} 与 {@link SandboxSkillStaging}）。两者缺一不可：只路由不投影的话，
 * 框架提示词里写给模型的 {@code /workspace/skills/<技能>} 在容器里根本不存在，模型会照着一个空路径去跑脚本。
 */
public final class AgentscopeRuntimeAdapter implements AgentRuntimePort {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeRuntimeAdapter.class);

    public static final String RUNTIME_ID = "agentscope";
    private static final String RUNTIME_VERSION = "2.0.3";

    /**
     * agent 名。**必须只在这里写一次**：框架用它当 agentId，而共享库的命名空间是
     * {@code agents/<agentId>/users/<用户>/<段>}——名字写两处，两处不一致时技能就会"写进去读不出来"。
     */
    private static final String AGENT_NAME = "doctor-data-assistant";

    /**
     * 共享 agent 的缓存上限。
     *
     * <p>键的取值空间是「工具面 × 模型 × 迭代上限」，正常情况下是个位数（几个角色各一份）；
     * 给 32 是留足余量。上限的意义是**兜底**：管理端反复换模型 / 反复改授权会让旧键变冷，
     * 这里满了就淘汰并 {@code close()}，内存与框架静态注册表都不会随历史变更无限长。
     */
    private static final int MAX_CACHED_AGENTS = 32;

    /**
     * 框架的技能读取工具名（{@code DynamicSkillMiddleware} 注册的那个）。
     *
     * <p>模型靠它把 {@code SKILL.md} 读出来——技能清单只是「有哪些技能」，正文要按需取。
     * 名字是从框架的提示词模板里读出来的（{@code load_skill_through_path(skillId="..", path="..")}）。
     * 框架哪天改了这个名字，{@link #buildAgent} 里的那条告警会喊出来，不会静默失效。
     */
    private static final String SKILL_READ_TOOL = "load_skill_through_path";

    private final ModelProvider modelProvider;
    private final AgentStateStore stateStore;
    private final Path workspace;

    /**
     * 多实例共享的工作区存储（H-04）：{@code null} = 关闭，工作区文件只在这台实例的本地磁盘上。
     *
     * <p>它装的是「agent 的那块文件空间」——技能（SKILL.md 与配套脚本）、工作区里被写下的中间文件都在里面。
     * 单实例时本地目录就够；多实例时本地目录会变成「甲机器写的技能，乙机器看不见」，所以这一项给了个
     * 共用的落点（{@code agent-service.workspace-store=jdbc}，见 {@code PlatformWorkspaceStore}）。
     *
     * <p><b>未开启时行为与改造前完全一致</b>：连 {@code filesystem(...)} 都不调用，框架维持自己的默认。
     */
    private final BaseStore workspaceStore;

    /**
     * 技能下发钩子（H-06a）：每一轮开始前把「这个用户可见的技能」同步进他的工作区。
     *
     * <p>{@code NONE} = 不装（默认）。装上它同时会打开框架的技能发现——两者必须成对，
     * 只写文件不打开发现，模型看不见；只打开发现不写文件，技能目录是空的。
     */
    private final SkillProvisioner skillProvisioner;

    /**
     * 沙箱设置（H-05）：命令是在容器里跑，还是根本没有执行面。
     *
     * <p>默认 {@link SandboxSettings#disabled()}，也就是**这台实例不开沙箱**：没有容器、没有壳工具，
     * 与改造前的行为逐字一致。开着的时候容器当主文件系统、共享前缀走路由（见 {@link SandboxSharedRoutes}），
     * 并且壳工具会被放行——那是开关打开后唯一故意偏离 ADR-31 基线的地方。
     */
    private final SandboxSettings sandbox;

    /**
     * 技能落盘（H-06b）：把共享库里的技能抄到本机，供框架投影进容器。
     *
     * <p>只有开了沙箱才建（关着的时候这条路上什么都没有，连目录都不会出现）；
     * 目录落在平台自己的 {@code workspace-dir} 下，切环境时跟着它走。
     */
    private final SandboxSkillStaging skillStaging;

    /**
     * 沙箱工件目录（2026-09-27）：容器里的 {@code <workspaceRoot>/out} 绑在宿主机的
     * {@code <workspace-dir>/sandbox-artifacts/<用户>} 上。
     *
     * <p>为什么需要它：容器一轮一个、每轮结束被框架删掉，而「先生成、确认后再上传」天生跨轮——
     * 没有这块宿主目录，第二轮就只能拿到一台空容器（详见 {@link SandboxArtifactMount}）。
     * 不开沙箱时为 {@code null}（这条路上什么都没有）。
     */
    private final SandboxArtifactMount artifactMount;

    /**
     * 一次性确认凭据的登记口（§19.9）：用户批准写操作时用。
     *
     * <p>{@code NONE} = 不登记（等同改造前：写操作在网关 G3 处 403 {@code CONFIRM_REQUIRED}）。
     */
    private final ConfirmRegistrar confirmRegistrar;

    private final SharedAgentPool agents = new SharedAgentPool(MAX_CACHED_AGENTS);

    /** 探容器的超时：本机 docker CLI 的正常回话时间是几十毫秒，20 秒已经非常宽松。 */
    private static final int CONTAINER_PROBE_TIMEOUT_SECONDS = 20;

    /**
     * 「这台容器还在不在」的探针：在就返回它的状态（running / exited / created…），查不到返回 {@code null}。
     *
     * <p>做成字段而不是静态方法的理由只有一条：**用例得能换掉它**——否则每个用例都要真去叫一次
     * docker（CI 上没有 Docker，用例就得整体跳过，而跳过等于没测）。
     */
    private Function<String, String> containerStatusProbe = AgentscopeRuntimeAdapter::dockerContainerStatus;

    /** 供用例注入假探针；生产代码不要用（见 {@link #containerStatusProbe}）。 */
    void setContainerStatusProbeForTest(Function<String, String> probe) {
        this.containerStatusProbe = probe == null ? AgentscopeRuntimeAdapter::dockerContainerStatus : probe;
    }

    /** 工作区不共享（默认）：工作区文件只落在这台实例的本地磁盘上。 */
    public AgentscopeRuntimeAdapter(ModelProvider modelProvider, AgentStateStore stateStore, Path workspace) {
        this(modelProvider, stateStore, workspace, null, SkillProvisioner.NONE, SandboxSettings.disabled());
    }

    /**
     * 带共享工作区的构造器（H-04）。
     *
     * @param workspaceStore 多实例共用的工作区存储；{@code null} = 不共享（等同上面那个构造器）
     */
    public AgentscopeRuntimeAdapter(
            ModelProvider modelProvider, AgentStateStore stateStore, Path workspace, BaseStore workspaceStore) {
        this(modelProvider, stateStore, workspace, workspaceStore, SkillProvisioner.NONE, SandboxSettings.disabled());
    }

    /**
     * 带技能下发的构造器（H-06a）。
     *
     * @param skillProvisioner 技能内容下发；{@code SkillProvisioner.NONE} = 不装（等同上面那个构造器）
     */
    public AgentscopeRuntimeAdapter(
            ModelProvider modelProvider,
            AgentStateStore stateStore,
            Path workspace,
            BaseStore workspaceStore,
            SkillProvisioner skillProvisioner) {
        this(modelProvider, stateStore, workspace, workspaceStore, skillProvisioner, SandboxSettings.disabled());
    }

    /**
     * 全量构造器（H-05）。前几个构造器都是它的简写形式，缺省就是"不开沙箱"。
     *
     * @param sandbox 沙箱设置；{@code SandboxSettings.disabled()} = 不开（等同上面几个构造器）
     */
    public AgentscopeRuntimeAdapter(
            ModelProvider modelProvider,
            AgentStateStore stateStore,
            Path workspace,
            BaseStore workspaceStore,
            SkillProvisioner skillProvisioner,
            SandboxSettings sandbox) {
        this(modelProvider, stateStore, workspace, workspaceStore, skillProvisioner, sandbox, ConfirmRegistrar.NONE);
    }

    /**
     * 全量构造器（H-05 + §19.9 的写操作确认）。
     *
     * @param confirmRegistrar 写操作确认凭据的登记口；{@code null} = {@link ConfirmRegistrar#NONE}
     */
    public AgentscopeRuntimeAdapter(
            ModelProvider modelProvider,
            AgentStateStore stateStore,
            Path workspace,
            BaseStore workspaceStore,
            SkillProvisioner skillProvisioner,
            SandboxSettings sandbox,
            ConfirmRegistrar confirmRegistrar) {
        this.confirmRegistrar = confirmRegistrar == null ? ConfirmRegistrar.NONE : confirmRegistrar;
        this.modelProvider = modelProvider;
        this.stateStore = stateStore;
        this.workspace = workspace;
        this.workspaceStore = workspaceStore;
        this.skillProvisioner = skillProvisioner == null ? SkillProvisioner.NONE : skillProvisioner;
        this.sandbox = sandbox == null ? SandboxSettings.disabled() : sandbox;
        this.skillStaging = this.sandbox.enabled()
                ? new SandboxSkillStaging(workspace.resolve("sandbox-skills"))
                : null;
        this.artifactMount = this.sandbox.enabled()
                ? new SandboxArtifactMount(workspace.resolve("sandbox-artifacts"))
                : null;
    }

    @Override
    public String id() {
        return RUNTIME_ID;
    }

    @Override
    public String version() {
        return RUNTIME_VERSION;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, true, true, true, false, false, true);
    }

    @Override
    public AgentSession start(AgentRunRequest request) {
        HarnessAgent agent = agentFor(request);
        // 新会话开场就先同步一次技能：不等第一轮跑起来，工作区里就已经是这一轮该有的那份。
        // 与 stream() 里那一次是同一个动作、同一份语义（幂等），两处都留着是因为「新会话」
        // 与「接着聊」走的是两条不同的代码路径。
        provisionSkills(agent, request, RuntimeContext.builder()
                .userId(request.userId())
                .sessionId(request.sessionId())
                .build());
        return new AgentscopeSession(request.sessionId(), agent, request);
    }

    /**
     * 取这把键对应的共享 agent（没有就建一个）。
     *
     * <p>为什么「建」这件事值得缓存：{@code build()} 要扫技能目录、读 tools.json、装十几个中间件，
     * 而它装出来的东西**只跟工具面/模型/迭代上限有关**——跟「谁在用、哪一轮」无关。
     * 原来每次 {@code start()} 都建一个，等于每轮都付一次装配成本，还会在框架的静态
     * {@code GracefulShutdownManager} 里越堆越多（L-06）。
     */
    private HarnessAgent agentFor(AgentRunRequest request) {
        return agents.get(agentKey(request), () -> buildAgent(request));
    }

    /**
     * 共享键 = 工具面 + 模型 + 迭代上限。
     *
     * <p>三样东西的取舍：
     * <ul>
     *   <li><b>工具面</b>：工具是注册在 agent 的 Toolkit 上的，看不见的接口不能留在上面（越权）。
     *       签名用「工具名 + 内容哈希」而不是只留名字：管理端改了某个接口的入参 schema 之后，
     *       同名工具的定义变了，靠名字就认不出来，会拿旧的 schema 去问模型；</li>
     *   <li><b>模型</b>：模型是 agent 级字段，由部署配置决定（ADR-14）。用
     *       {@link ModelProvider#modelKeyFor} 拿它的身份而不是对象本身，管理端换供应商后
     *       下一轮自然用新的（旧键变冷被淘汰）；</li>
     *   <li><b>迭代上限</b>：同样是 agent 级字段。</li>
     * </ul>
     *
     * <p>**刻意不在键里**：{@code userId} / {@code sessionId}（那才是每次调用的东西，
     * 框架按它们各自装一份状态）与系统提示词（每轮都变，走中间件注入）。
     */
    private String agentKey(AgentRunRequest request) {
        StringBuilder key = new StringBuilder(modelProvider.modelKeyFor(request))
                .append('|')
                .append(request.maxIters())
                .append('|');
        request.tools().all().stream()
                .map(spec -> spec.name() + "@" + spec.hashCode())
                .sorted()
                .forEach(signature -> key.append(signature).append(','));
        return key.toString();
    }

    private HarnessAgent buildAgent(AgentRunRequest request) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(AGENT_NAME)
                .model(modelProvider.modelFor(request))
                .maxIters(request.maxIters())
                .stateStore(stateStore)
                // 工作目录钉在平台 workspace-dir 下（只放运行痕迹，会话状态在 PG）：HarnessAgent 默认按 cwd 建 .agentscope/，
                // 实测会把会话原文与「记忆账本」写进应用启动目录（仓库根），既是数据外泄也是仓库污染。
                .workspace(workspace)
                // 系统提示词按轮注入：它每轮都变（今天/本月 + 本轮接口清单），见 PerCallSystemPrompt。
                .middleware(new PerCallSystemPrompt())
                .disableFilesystemTools()
                // 壳工具见下面沙箱那一段：没开沙箱时它没有可执行的场所，必须关掉（ADR-31 基线）。
                // 记忆类能力一律关闭（ADR-31 基线）：memory tools 会改模型上下文，
                // memory hooks / compaction / 结果驱逐会**每轮偷偷多调模型**（实测每轮 2 次调用），
                // 这些调用既不在平台成本上报里、也不在事件流里，属于「看不见的动作」。
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableCompaction()
                .disableToolResultEviction()
                // 会话正文的权威是框架自己的状态库，但不另存一份 transcript / session 文件（§19.6）。
                .disableTranscript()
                .disableSessionPersistence()
                .disableWorkspaceContext()
                // 子代理一律关掉：它们会自己调模型、自己用工具，绕开平台的工具白名单与审计（ADR-31 基线）。
                .disableSubagents()
                .disableDynamicSubagents();
        if (!sandbox.enabled()) {
            // 没开沙箱 = 这台实例**没有**执行面：模型不能跑命令，工具执行一律回到平台 ToolInvoker（ADR-31 基线）。
            // 开了沙箱才放行壳工具，那时它的执行场所是容器，不是这台机器（见下面的沙箱装配）。
            builder.disableShellTool();
        }
        if (skillProvisioner == SkillProvisioner.NONE) {
            // 不装技能下发时，技能也不许被发现：技能内容来自平台的注册表与对象存储，
            // 不是「工作区里恰好有什么文件」。不关掉的话，任何能往工作区写文件的东西都能变出一个技能。
            builder.disableDynamicSkills().disableDefaultWorkspaceSkills();
        }
        if (sandbox.enabled()) {
            // 沙箱模式（H-05）：**容器当主文件系统**——模型跑命令、写临时文件都在容器里，碰不到这台机器。
            //
            // 但「整块工作区都在容器里」不行：那样多副本就散了（甲机器容器里写的技能，乙机器看不见）。
            // 所以把「必须跨实例看到同一份」的前缀一条条摘出来交给共享库（默认只有 skills/，见
            // SandboxSettings#sharedPrefixes）。前缀必须**逐个枚举**且按目录边界写：写成单个斜杠一条都匹配不上
            // （台账 L-15）。
            //
            // 隔离粒度同样是 USER：一个用户一个容器，一个用户一个共享命名空间。
            builder.filesystem(sandbox.toFilesystemSpec());
            if (workspaceStore != null) {
                for (Map.Entry<String, AbstractFilesystem> route : SandboxSharedRoutes.build(
                                workspaceStore, workspace, AGENT_NAME, IsolationScope.USER, sandbox.sharedPrefixes())
                        .entrySet()) {
                    builder.filesystemRoute(route.getKey(), route.getValue());
                    log.info("沙箱共享路径：{} 仍然读共享库（其余路径在容器里）", route.getKey());
                }
            } else {
                // 开了沙箱却没开共享库：能跑，但「甲机器容器里写的技能乙机器看不见」——这是配置问题，不是 bug。
                log.warn("沙箱已启用，但 agent-service.workspace-store 仍是 none：工作区只在容器里，"
                        + "多副本之间不共享（技能下发也会各写各的）");
            }
        } else if (workspaceStore != null) {
            // 工作区搬到共享存储（H-04）：任意实例写、任意实例读，看到的是同一份文件。
            //
            // 隔离范围取 USER（框架默认值）：一个用户一个命名空间——这正是「我的技能你看不见、
            // 你的技能看不见」要的粒度（H-06 的技能按人隔离就架在这个粒度上）。
            // 取 SESSION 会把同一个用户的技能按会话复制很多份，取 GLOBAL 则会让所有用户串在一起。
            //
            // 注意这里只是把「文件放哪儿」换掉了，**没有**打开任何 agent 侧的文件 / 命令能力：
            // 上面的 disableFilesystemTools 没动、壳工具也关着，文件工具仍然只在平台手里。
            builder.filesystem(new RemoteFilesystemSpec(workspaceStore).isolationScope(IsolationScope.USER));
        }
        HarnessAgent agent = builder.build();
        Toolkit toolkit = agent.getToolkit();
        Set<String> platformToolNames = new LinkedHashSet<>();
        for (ToolSpec spec : request.tools().all()) {
            toolkit.registerAgentTool(new PlatformToolAdapter(spec));
            platformToolNames.add(spec.name());
        }
        // 工具面白名单：HarnessAgent 会**无条件**塞进 web_search / web_fetch / wait_async_results
        // 等内建工具（实测 builder 上没有任何开关能关掉它们）。这些工具绕开平台的 ToolInvoker，
        // 于是也就绕开了 DENY 判定、确认闸口与审计（§18.11.3），对医生助手还是一条
        // 「把提示词里的患者信息发到公网」的现成通道。
        // 规则取最强的一条：**平台没注册的，一律移掉**——这样将来框架升级偷偷加工具也进不来。
        // 例外只有两类，都是**读工作区 / 在沙箱里跑**这种不碰数据面、不出网的动作：
        //  ① 技能读取工具（下面的 SKILL_READ_TOOL）：把工作区里那个技能的说明书读出来；
        //  ② 沙箱里的壳工具（ShellExecuteTool）：只有 agent-service.sandbox.enabled=true 时才存在，
        //     它的执行场所是容器（不是这台机器），开着沙箱就是要它跑脚本（H-06b）。
        for (String foreign : new ArrayList<>(toolkit.getToolNames())) {
            if (!platformToolNames.contains(foreign) && !allowedBuiltinTool(foreign)) {
                toolkit.removeTool(foreign);
                log.info("已移除运行时内建工具（不在平台工具清单内）：{}", foreign);
            }
        }
        if (skillProvisioner != SkillProvisioner.NONE && !toolkit.getToolNames().contains(SKILL_READ_TOOL)) {
            // 技能发现打开了、读取工具却不在：要么框架换了工具名，要么这个版本的框架没注册它。
            // 这时模型能看到技能清单却读不到正文，属于「静默半残」——喊出来。
            log.warn("技能发现已打开，但没有看到技能读取工具 {}（框架可能改了名字）：技能正文将读不到", SKILL_READ_TOOL);
        }
        log.info("已装配共享 agent：工具面 {} 个，模型 {}，maxIters={}", platformToolNames.size(),
                modelProvider.modelKeyFor(request), request.maxIters());
        return agent;
    }

    @Override
    public Flux<AgentEvent> stream(AgentSession session, AgentTurn turn) {
        AgentscopeSession agentscope = (AgentscopeSession) session;
        AgentRunRequest request = agentscope.request;
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();
        // 这一次调用的东西全部挂在 RuntimeContext 上（见 CallAttributes 的说明）：
        // 框架会带着它走完整个调用链，中间件与工具都能拿到，而 agent 实例本身保持无状态。
        var contextBuilder = RuntimeContext.builder()
                .userId(request.userId())
                .sessionId(session.sessionId())
                .put(CallAttributes.TURN_ID, turn.turnId())
                .put(CallAttributes.TRACE_ID, turn.traceId())
                .put(CallAttributes.REQUEST_ID, request.requestId())
                .put(CallAttributes.DEADLINE_EPOCH_MS, request.deadlineEpochMs())
                .put(CallAttributes.TOOL_INVOKER, request.toolInvoker())
                .put(CallAttributes.SYSTEM_PROMPT, request.systemPromptPrefix());
        // 用户刚批准过的写操作（§19.9）：把这一轮的一次性确认凭据带上，网关 G3 会消费它。
        // **取一次就清掉**：凭据是一次性的，留在会话上只会让后面几轮拿着废凭据去撞 403。
        String confirmIdForTurn = agentscope.takeConfirmIdForTurn();
        if (confirmIdForTurn != null) {
            contextBuilder.put(CallAttributes.CONFIRM_ID, confirmIdForTurn);
        }
        RuntimeContext context = contextBuilder.build();
        // 技能下发：**在提示词拼装之前**把这一轮可见的技能同步进工作区。
        // 为什么每轮都做：技能的可见性来自网关的授权，随时会被收走；而模型每一轮都要看当前这份。
        // 代价可控——没变的技能不会重写（见 WorkspaceSkillProvisioner 的类注释）。
        provisionSkills(agentscope.agent, request, context);
        // 技能投影（H-06b）：容器里也要有一份技能，技能里的脚本才跑得动。
        // 必须排在技能下发**之后**——下发的就是这一轮该有的那份，投影抄的正是它。
        prepareSandbox(agentscope.agent, context);
        mountSandboxArtifactReader(agentscope.agent, context);
        Msg message = buildMessage(agentscope, turn);
        return agentscope.agent
                .streamEvents(message, context)
                .mapNotNull(event -> {
                    // 框架在**执行之前**拦下写调用、要求用户确认（§19.9）：这里把被拦下的调用记在会话上。
                    // 少了这一步，用户点确认时平台不知道该确认哪一次调用、凭据也登记不出来——
                    // 症状就是「点了确认还是 403」（2026-09-27 实测）。
                    rememberPendingConfirmation(agentscope, event);
                    return translator.translate(event, session.sessionId(), turn.turnId(), turn.traceId())
                            .map(translated -> withConfirmPrompt(agentscope, translated))
                            .orElse(null);
                })
                .onErrorMap(AgentscopeRuntimeAdapter::actionableOrOriginal)
                // 本轮一结束就把沙箱状态作废（为什么必须在这里做，见 forgetSandboxState）。
                .doFinally(signal -> forgetSandboxState(context));
    }

    /** 把这一轮可见的技能同步进这个用户的工作区（H-06a）；没装下发时什么都不做。 */
    private void provisionSkills(HarnessAgent agent, AgentRunRequest request, RuntimeContext ctx) {
        if (skillProvisioner == SkillProvisioner.NONE) {
            return;
        }
        skillProvisioner.provision(agent.getWorkspaceManager().getFilesystem(), ctx, request.visibleSkills());
    }

    /**
     * 让容器这一轮也有一份技能（H-06b）。沙箱没开就什么都不做。
     *
     * <p>两件事，顺序不能反：
     * <ol>
     *   <li>先把上一轮留下的沙箱状态清掉（{@link #clearStaleSandboxState}）——容器一轮一个，
     *       旧状态只会让框架以为「投影早就做过了」；</li>
     *   <li>再把技能抄到本机（{@link SandboxSkillStaging}），并把「用这个目录当投影源」的沙箱上下文
     *       放进这一轮的 {@link RuntimeContext}——框架的调用链会带着它走，容器启动时照着投影。
     *       为什么走上下文而不是改 agent：agent 是所有用户共用的，投影源却是每个用户、每一轮都不同
     *       （技能授权随时会变），只有「每次调用」的地方才放得下它。</li>
     * </ol>
     */
    /**
     * 把「读沙箱产物」的口子挂到这一轮的 {@link RuntimeContext} 上（§18.5.3）。
     *
     * <p>沙箱没开就什么都不挂：没容器就没有可读的产物，挂了只会让上传接口在运行时报一句
     * 看不懂的错。挂不上的时候写操作照样能跑——模型直接把 base64 填进参数那条老路还在。
     */
    void mountSandboxArtifactReader(HarnessAgent agent, RuntimeContext ctx) {
        if (!sandbox.enabled()) {
            return;
        }
        AbstractFilesystem filesystem;
        try {
            filesystem = agent.getWorkspaceManager().getFilesystem();
        } catch (RuntimeException e) {
            log.warn("拿不到工作区文件系统，这一轮不带沙箱产物读取能力：原因={}", e.toString());
            return;
        }
        ctx.put(
                CallAttributes.SANDBOX_ARTIFACT_READER,
                (SandboxArtifactReader) path -> readArtifact(filesystem, ctx, path));
    }

    /**
     * 按路径读出工件字节：**先宿主工件目录、再容器**。
     *
     * <p>顺序不能反：产物本来就落在宿主目录里（{@link SandboxArtifactMount} 那条 bind mount），
     * 容器只是它的一个视图；而到了「用户确认之后」那一轮，容器已经是新的一台，只有宿主那份还在。
     * 先宿主还有一个好处：读字节不再依赖容器活着，确认链路上少一个可变量。
     */
    private Optional<byte[]> readArtifact(
            AbstractFilesystem filesystem, RuntimeContext ctx, String path) {
        String userId = ctx == null ? null : ctx.getUserId();
        if (artifactMount != null) {
            Optional<Path> hostPath = artifactMount.hostPathFor(userId, sandbox.workspaceRoot(), path);
            if (hostPath.isPresent()) {
                try {
                    if (Files.isRegularFile(hostPath.get())) {
                        return Optional.of(Files.readAllBytes(hostPath.get()));
                    }
                    log.debug("宿主工件目录里没有这个文件，改问容器：path={}", path);
                } catch (IOException e) {
                    log.warn("读宿主工件失败：path={} 原因={}", path, e.toString());
                }
            }
        }
        return downloadSandboxArtifact(filesystem, ctx, path);
    }

    /** 从沙箱里按路径读出字节；读不到返回空（调用方据此报「文件没找到」，而不是上传一份空文件）。 */
    private static Optional<byte[]> downloadSandboxArtifact(
            AbstractFilesystem filesystem, RuntimeContext ctx, String path) {
        try {
            List<FileDownloadResponse> responses = filesystem.downloadFiles(ctx, List.of(path));
            if (responses == null || responses.isEmpty()) {
                return Optional.empty();
            }
            FileDownloadResponse response = responses.get(0);
            if (response == null || !response.isSuccess() || response.content() == null) {
                return Optional.empty();
            }
            return Optional.of(response.content());
        } catch (RuntimeException e) {
            log.warn("读沙箱产物失败：path={} 原因={}", path, e.toString());
            return Optional.empty();
        }
    }

    void prepareSandbox(HarnessAgent agent, RuntimeContext ctx) {
        if (!sandbox.enabled() || skillStaging == null) {
            return;
        }
        resetSandboxStateIfContainerGone(ctx);
        Path stagingRoot = skillStaging.stage(agent.getWorkspaceManager().getFilesystem(), ctx);
        SandboxContext context = sandbox.toProjectingFilesystemSpec().toSandboxContext(stagingRoot);
        ctx.put(SandboxContext.class, withArtifactMount(context, ctx));
    }

    /**
     * 往框架给的工作区清单里加一条「工件目录」的 bind mount（{@link SandboxArtifactMount}）。
     *
     * <p>为什么要在框架造好的 {@link SandboxContext} 上再改一手、而不是让 {@code SandboxSettings} 直接带上它：
     * 挂载点是**按用户**分的，而且宿主目录必须真实存在——只有轮到这一轮、知道是谁在跑的时候才算得出来
     * （与技能投影源同一个理由，见 {@code SandboxSettings#toProjectingFilesystemSpec}）。
     *
     * <p>挂不上（建目录失败之类）不打挂这一轮：报表照样能生成、照样能上传，只是「确认后再执行」那一轮
     * 会因为容器里没有这个文件而失败——日志里写明原因，比让整轮起不来强。
     */
    private SandboxContext withArtifactMount(SandboxContext context, RuntimeContext ctx) {
        if (artifactMount == null) {
            return context;
        }
        try {
            Path userDir = artifactMount.prepareUserDir(ctx == null ? null : ctx.getUserId());
            WorkspaceSpec workspace = context.getWorkspaceSpec().copy();
            Map<String, WorkspaceEntry> entries = workspace.getEntries() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(workspace.getEntries());
            entries.put(SandboxArtifactMount.CONTAINER_DIR, artifactMount.entry(userDir));
            workspace.setEntries(entries);
            return SandboxContext.builder()
                    .client(context.getClient())
                    .clientOptions(context.getClientOptions())
                    .workspaceSpec(workspace)
                    .snapshotSpec(context.getSnapshotSpec())
                    .isolationScope(context.getIsolationScope())
                    .build();
        } catch (Exception e) {
            log.warn("沙箱工件目录挂不上（这一轮生成的工件在容器删除后会丢）：原因={}", e.toString());
            return context;
        }
    }

    /**
     * 探一下上一轮的沙箱状态还能不能用：**能用就留着（续用同一台容器），不能用才清掉**。
     *
     * <p><b>2026-09-27 改了行为，原因是一条真机证据</b>：原来这里是无条件清掉——当时的理由写在台账 L-20 里
     * （「容器一轮一个、不清的话第二轮看不到技能脚本」）。但真机日志揭示了更底下的一个 bug：
     * 框架给沙箱状态用的槽位号是路径式的（{@code sandbox/user/<agentId>/<userId>}），JDBC 状态库
     * （{@code JdbcAgentStateStore}）拒绝含 {@code /} 的槽位号，于是沙箱状态**从来就没写进去过**
     * （见 {@code PlatformAgentStateStore} 类注释里的「第二个洞」）。也就是说：L-20 观察到的
     * 「第二轮没有脚本」是**状态存不下来**的后果，而"清掉"只是把这件事变得看起来像设计。
     *
     * <p>状态能存下来之后，正确行为就回到框架的设计上（{@code SandboxManager#acquire} 的第 3 优先级）：
     * 读回上一轮的状态 → {@code resume} 同一台容器 → {@code AbstractBaseSandbox#start} 走
     * <b>Branch A：workspace preserved</b> —— 容器里上一轮生成的文件**还在**。
     * 这一条恰恰是「先生成文件、用户确认后再上传」必须的：确认会把一轮拆成两轮，容器每轮重建的话，
     * 第二轮连文件都找不到（2026-09-27 实测：模型只能把 base64 分几次打印出来再拼，拼出来的 xlsx 是坏的）。
     *
     * <p>什么时候还是要清：**容器真的没了**（Docker 重启、{@code docker prune}、手工删过）。
     * 那时状态里指向的容器 id 已经查不到，续用等于让框架去 {@code docker start} 一台不存在的容器——
     * {@code SandboxLifecycleMiddleware} 会把这种失败往上抛，整轮直接挂掉。所以这里问一次 Docker：
     * 容器还在就留着，查不到就清掉，让框架按全新沙箱处理（起容器 + 应用工作区 + 重新投影）。
     *
     * <p>问不到答案时（docker 命令不可用、容器状态读取异常）**倾向保留**：这是框架的原生行为，
     * 而且真出问题时下一轮的错误信息里会明明白白写着容器 id，比"悄悄换了一台新容器"好查。
     *
     * <p>注意清的是**沙箱状态**（框架自己的 {@code _sandbox_state} 槽位），不是会话状态——
     * 聊了什么在另一把键上，照样跨实例、跨轮次（见类注释里 A/B/C 那一段）。
     */
    private void resetSandboxStateIfContainerGone(RuntimeContext ctx) {
        try {
            Optional<SandboxIsolationKey> key = SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_NAME);
            if (key.isEmpty()) {
                return;
            }
            SessionSandboxStateStore sandboxState = new SessionSandboxStateStore(stateStore, AGENT_NAME);
            String containerId = persistedContainerId(sandboxState, key.get());
            if (containerId == null) {
                // 没有可续用的记录（第一次用、或上一轮没存下来）：本轮本来就是全新容器，不需要清。
                return;
            }
            String status = containerStatusProbe.apply(containerId);
            if (status != null) {
                log.debug("[sandbox] 上一轮的容器 {} 还在（status={}），续用：容器里上一轮的文件保留", containerId, status);
                return;
            }
            // 容器查不到：留着这条状态的话，框架会去 start 一台不存在的容器，整轮直接失败。
            sandboxState.delete(key.get());
            log.info("[sandbox] 上一轮的容器 {} 已经不在了，清掉沙箱状态，本轮起一台新容器", containerId);
        } catch (Exception e) {
            log.warn("沙箱状态检查失败（这一轮容器里可能没有技能脚本）：原因={}", e.toString());
        }
    }

    /**
     * 本轮跑完就把沙箱状态作废：**下一轮不许复用这一轮那条状态**。
     *
     * <h2>为什么必须作废</h2>
     * 这一轮结束（{@code SandboxLifecycleMiddleware#releaseForCall}）时框架做两件事：先把状态写回
     * （{@code SandboxManager#persistState}：里面记着本轮那台容器 id 与 {@code workspaceProjectionHash}），
     * 再把容器停掉并删除（{@code SandboxManager#release} → {@code shutdown} → {@code docker rm}）。
     * 于是下一条状态天生就是「指向一台已经不存在的容器」。
     *
     * <p>框架在下一轮读回这条状态后走的是恢复路径：容器查不到 → 新建一台空的（
     * {@code DockerSandbox#doEnsureContainerRunning}），工作区按 {@code Branch D} 重来。技能投影却在
     * {@code AbstractBaseSandbox#applyWorkspaceProjectionIfChanged} 里**按内容哈希跳过**——哈希记在
     * 上面那条状态里，重建后的空容器于是一个文件都拿不到。后果不是"少个优化"，而是
     * **容器里没有技能脚本**：模型照着技能文档给的 {@code /workspace/skills/<技能>/scripts/...}
     * 去执行，得到的是一句 {@code No such file or directory}（2026-09-27 真机实测，用例
     * {@code SandboxDockerEndToEndTest} 里「第二轮」那一段钉住了框架这个行为）。
     *
     * <h2>为什么放在「本轮结束」而不是「下一轮开头」</h2>
     * 下一轮开头那次清理（{@link #resetSandboxStateIfContainerGone}）与框架的
     * {@code releaseForCall} 存在**先后不可控的竞争**：同一个用户的甲轮还没释放、乙轮已经在准备，
     * 甲轮随后写回的状态会把乙轮刚清掉的那条复活。放在本轮结束——也就是框架写回之后——这个窗口
     * 才关得上（同一个用户同时只有一轮在沙箱里，跨实例由 H-11 那把执行锁保证）。
     *
     * <p>代价：跨轮"续用同一台容器"的能力没了（{@code Branch A} 永远走不到）。这不是新增的损失——
     * 容器本来就在每轮结束时被删掉，那条路一直是断的；真正跨轮要活着的是**产物**，那走的是
     * 宿主机工件目录的 bind mount（{@code SandboxArtifactMount}），与沙箱状态无关。
     *
     * <p>失败只记日志：清不掉最多是下一轮容器里没有技能脚本，不该把这一轮已经跑完的结果带塌。
     */
    void forgetSandboxState(RuntimeContext ctx) {
        try {
            Optional<SandboxIsolationKey> key = SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_NAME);
            if (key.isEmpty()) {
                return;
            }
            new SessionSandboxStateStore(stateStore, AGENT_NAME).delete(key.get());
        } catch (Exception e) {
            log.warn("沙箱状态没清掉（下一轮的容器里可能没有技能脚本）：原因={}", e.toString());
        }
    }

    /** 上一轮留下的状态里记着的那台容器 id；没有状态 / 解析不出来时返回 {@code null}。 */
    private static String persistedContainerId(SessionSandboxStateStore sandboxState, SandboxIsolationKey key)
            throws Exception {
        Optional<String> json = sandboxState.load(key);
        if (json.isEmpty() || json.get().isBlank()) {
            return null;
        }
        SandboxState state = new DockerSandboxClient().deserializeState(json.get());
        if (state instanceof DockerSandboxState docker) {
            String containerId = docker.getContainerId();
            return containerId == null || containerId.isBlank() ? null : containerId;
        }
        return null;
    }

    /** 问 Docker 这台容器还在不在；在就返回它的状态（running / exited / created…），查不到返回 {@code null}。 */
    private static String dockerContainerStatus(String containerId) {
        try {
            Process process = new ProcessBuilder(
                            "docker", "inspect", "--format", "{{.State.Status}}", containerId)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(CONTAINER_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 && !out.isBlank() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 框架内建工具里，哪些**允许留下**（其余一律移除，见 {@link #buildAgent} 里那段说明）。
     *
     * <p>两类例外，前提都是"不碰数据面、不出网"：
     * <ul>
     *   <li><b>技能读取工具</b>：只有装了技能下发才放行——没装下发的话工作区里根本没有技能，
     *       放行它等于给模型一个永远读不到东西的工具；</li>
     *   <li><b>沙箱里的壳工具</b>：只有开了沙箱才存在（框架在"文件系统是沙箱"时才注册它），
     *       这里是第二道门——就算框架哪天在别的模式下也把它塞进来，没开沙箱一样会被移掉。</li>
     * </ul>
     */
    private boolean allowedBuiltinTool(String toolName) {
        if (isSkillReadTool(toolName)) {
            return true;
        }
        return sandbox.enabled() && ShellExecuteTool.NAME.equals(toolName);
    }

    /**
     * 是不是平台要放行的那个技能读取工具。
     *
     * <p>只有**装了技能下发**（{@code agent-service.workspace-skills=workspace}）时才放行。
     */
    private boolean isSkillReadTool(String toolName) {
        return skillProvisioner != SkillProvisioner.NONE && SKILL_READ_TOOL.equals(toolName);
    }

    /**
     * 把模型侧的 HTTP 失败翻译成「能照着做」的说明。
     *
     * <p>为什么不翻译就只能说「服务暂不可用」：模型服务用状态码区分了「你能改的」和「只能等的」。
     * 401/403/404 是前者——运维去管理端改一个字段就好；混进「请稍后再试」里，用户唯一能做的就是反复重试。
     *
     * <p>为什么在运行时这一层做：{@code ModelHttpException} 是框架里**协议无关**的接口，只暴露状态码，
     * 按状态码翻译，换成 OpenAI / Kimi / GLM / MiniMax 一样成立，
     * 接入层不必去认识任何一家供应商的异常类。
     *
     * <p>输出纪律（§20.1.6 硬约束第 7 条）：只吐**写死的常量文本**，绝不带上游响应体、URL 或密钥——
     * 上游回包是对方可控的内容，有回显请求头的可能。
     */
    static Throwable actionableOrOriginal(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 8 && cause != null; depth++) {
            if (cause instanceof ModelHttpException http && http.getStatusCode() != null) {
                String message = MISCONFIGURED_BY_STATUS.get(http.getStatusCode());
                return message == null ? error : new RuntimeMisconfiguredException(message);
            }
            // 「还有一张写操作确认卡没答复」也属于**照做就能好**的一类，不能沉到兜底那句
            // 「服务暂不可用，请稍后再试」里——那句话会让用户一直重试一件永远不会成功的事。
            if (isPendingConfirmation(cause)) {
                return new PendingConfirmationException();
            }
            cause = cause.getCause();
        }
        return error;
    }

    /**
     * 框架对这种情况用的是 {@link IllegalStateException} + 一段英文说明
     * （{@code "Agent is paused for human-in-the-loop confirmation…"}）。
     *
     * <p>为什么按消息文本认、而不是按异常类型：框架没有给这一类错误专门的类型，它也**不该**有
     * ——「有待确认的写调用」是平台侧的语义（§19.9 那张卡是平台弹的）。文本匹配退化的风险很小：
     * 认不出来只是回到原来的「服务暂不可用」，不会把别的失败误判成待确认。
     */
    private static boolean isPendingConfirmation(Throwable cause) {
        if (!(cause instanceof IllegalStateException)) {
            return false;
        }
        String message = cause.getMessage();
        return message != null && message.contains("human-in-the-loop confirmation");
    }

    /** 状态码 → 给用户/运维看的、可执行的说明。 */
    private static final Map<Integer, String> MISCONFIGURED_BY_STATUS = Map.of(
            400, "模型服务拒绝了这次请求（400）：请管理员在管理端「模型供应商」页核对接口地址与模型名",
            401, "模型服务拒绝了这个密钥（401）：请管理员在管理端「模型供应商」页更新 API Key",
            403, "这个密钥没有调用该模型的权限（403）：请管理员在管理端「模型供应商」页核对模型名与账号权限",
            404, "模型服务返回「不存在」（404）：请管理员在管理端「模型供应商」页核对接口地址与模型名",
            422, "模型服务认为请求不可处理（422）：请管理员在管理端「模型供应商」页核对模型名与接口地址");

    /**
     * 用户消息的正文块：**第一块是用户原话，第二块才是平台附的「本轮上下文快照」**。
     *
     * <p>快照为什么贴在这里、而不只在系统提示词里：老会话的历史紧挨着新提问，模型会照抄自己上一轮
     * 关于能力的说法（实测见 {@link AgentTurn#ATTR_CONTEXT_REMINDER}）。贴到问题之后是唯一"比历史更近"的位置。
     *
     * <p>**为什么拆成两个块而不是拼成一整段字符串**：会话正文现在存在框架状态里，
     * 而历史会话是**从状态投影出来的**。拼成一整段的话，用户的气泡里会多出那段给模型看的快照
     * （数据污染）；拆成块之后，「第一块 = 用户原话」就是一条能照着写的规则，
     * 平台也再不需要为了显示历史去反推、切割自己当初拼进去的字符串。
     *
     * <p>快照块以换行开头：{@code Msg.getTextContent()} 用 {@code "\n"} 连接各块，
     * 于是模型看到的整段文字与改造前逐字一致（没有悄悄改变提示词）。
     */
    static List<ContentBlock> contentFor(String userText, Map<String, Object> attributes) {
        List<ContentBlock> blocks = new ArrayList<>();
        String text = userText == null ? "" : userText;
        if (!text.isEmpty()) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        String reminder = reminderOf(attributes);
        if (!reminder.isEmpty()) {
            blocks.add(TextBlock.builder().text("\n" + reminder).build());
        }
        return blocks;
    }

    private static String reminderOf(Map<String, Object> attributes) {
        Object reminder = attributes == null ? null : attributes.get(AgentTurn.ATTR_CONTEXT_REMINDER);
        return reminder == null ? "" : String.valueOf(reminder).trim();
    }

    private static Msg buildMessage(AgentscopeSession session, AgentTurn turn) {
        UserMessage.Builder builder = UserMessage.builder();
        if (!session.pendingConfirmResults.isEmpty()) {
            builder.metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.copyOf(session.pendingConfirmResults)));
            session.pendingConfirmResults.clear();
        }
        return builder.content(contentFor(turn.userText(), turn.attributes())).build();
    }

    @Override
    public void confirm(AgentSession session, ConfirmDecision decision) {
        AgentscopeSession agentscope = (AgentscopeSession) session;
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock toolCall : agentscope.pendingToolCalls) {
            results.add(new ConfirmResult(decision.approved(), toolCall));
        }
        if (decision.approved() && !agentscope.pendingToolCalls.isEmpty()) {
            // 用户批准了：**平台这时才登记那张网关认的凭据**（§19.9）。
            // 框架那套确认回答的是「要不要执行这次工具调用」，它给的 id 不是网关的 confirmId；
            // 不在这里补一张，写操作到了网关 G3 就是 CONFIRM_REQUIRED 403（2026-09-27 实测）。
            // 一次批准登记一张：一轮里出现第二次写调用会再次被拦下、再问用户一次——宁可多问，不静默放行。
            agentscope.setConfirmIdForTurn(registerConfirm(agentscope, decision));
        }
        agentscope.pendingConfirmResults.addAll(results);
        agentscope.pendingToolCalls.clear();
        agentscope.markAnswered(agentscope.pendingConfirmPrompt, decision.approved());
        // 这张卡已经答复过了：从「当前挂起」摘掉，避免下一次拦截复用一个前端已经开始怀疑的编号；
        // 但**留一份**给紧随其后的那一轮——框架会回一个 CONFIRM_RESULT 事件，界面上那张卡要凭
        // 这个编号才认得出「说的就是我」（见 withAnsweredPrompt）。
        agentscope.setPendingConfirmPrompt(null);
    }

    /** 登记一张写操作凭据；登记不了（没装 / 落库失败）返回 {@code null}，让这一轮照旧被网关拦下。 */
    private String registerConfirm(AgentscopeSession session, ConfirmDecision decision) {
        AgentRunRequest request = session.request;
        String summary = "用户确认执行写操作：" + toolNamesOf(session.pendingToolCalls);
        try {
            return confirmRegistrar.register(
                    request.userId(), session.sessionId(), decision.confirmId(), summary);
        } catch (RuntimeException e) {
            log.warn("登记写操作确认凭据失败（这一轮的写调用会被网关拦下）：userId={}", request.userId(), e);
            return null;
        }
    }

    private static String toolNamesOf(List<ToolUseBlock> toolCalls) {
        return toolCalls.stream()
                .map(AgentscopeRuntimeAdapter::nameOf)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("（未识别）");
    }

    private static String nameOf(ToolUseBlock toolCall) {
        try {
            return toolCall.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 停止这一轮：**必须带上会话身份**再喊停。
     *
     * <p>框架有两个 interrupt 重载，差别很关键：无参的那个（已标 {@code @Deprecated}）打的是
     * 「默认槽位」{@code (null, defaultSessionId)}，而平台的每一轮都跑在自己的
     * {@code (userId, sessionId)} 槽位上——打错了位置等于没停。
     *
     * <p>这不是推测，是实测出来的：一个每 100ms 吐一个词的慢模型，无参 interrupt 之后
     * 15 个词一个不少地产出完（那一轮从头跑到底）；换成带身份的 interrupt，
     * 下一处检查点就会抛 {@code InterruptedException}，这一轮当场结束。
     *
     * <p>为什么会这样：框架的打断标记挂在**会话状态**上（{@code AgentState.interruptControl()}），
     * 每一轮的循环走到检查点时读的是本会话那一份；喊在别的槽位上，本会话什么都看不到。
     */
    @Override
    public void cancel(AgentSession session, String reason) {
        AgentscopeSession agentscope = (AgentscopeSession) session;
        agentscope.agent.interrupt(agentscope.request.userId(), session.sessionId());
    }

    @Override
    public Snapshot snapshot(AgentSession session) {
        AgentscopeSession agentscope = (AgentscopeSession) session;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentscope.agent.getAgentId());
        payload.put("maxIters", agentscope.agent.getMaxIters());
        payload.put("stateStore", stateStore.getClass().getName());
        return new Snapshot(RUNTIME_ID, RUNTIME_VERSION, session.sessionId(), System.currentTimeMillis(), payload);
    }

    @Override
    public AgentSession resume(String sessionId, Snapshot snapshot, AgentRunRequest request) {
        if (snapshot != null && !RUNTIME_ID.equals(snapshot.runtimeId())) {
            throw new RuntimeMismatchException(sessionId, RUNTIME_ID, snapshot.runtimeId());
        }
        // 状态由 AgentStateStore 按 (userId, sessionId) 寻址；重建会话句柄即恢复（§19.5）。
        return new AgentscopeSession(sessionId, agentFor(request), request);
    }

    /**
     * 一轮结束后释放本实例为这个会话留下的缓存（DR-22）。
     *
     * <p>为什么必须显式清：共享 agent 上有几张**没有上限**的会话表（状态缓存、权限引擎缓存），
     * 它们按 {@code (userId, sessionId)} 分段。不清就等于「访问过的会话数量」决定内存占用。
     * 清掉是安全的：配了状态库时，框架**每次调用开始都会重新从库里加载**这一段状态
     * （这正是多副本能接上同一个会话的原因），本地缓存只是同一轮里的复用。
     *
     * <p>清失败只记日志：这是清理动作，不该让已经跑完的一轮变成失败。
     */
    @Override
    public void release(AgentSession session) {
        if (!(session instanceof AgentscopeSession agentscope)) {
            return;
        }
        try {
            agentscope.agent.clearStateCache(agentscope.request.userId(), session.sessionId());
        } catch (RuntimeException e) {
            log.warn("清理会话缓存失败（不影响本轮结果）：sessionId={}", session.sessionId(), e);
        }
    }

    /**
     * 会话句柄作废 = 释放缓存，**不关 agent**。
     *
     * <p>共享 agent 是多个会话共用的，谁都不能在「自己这一轮结束」时把它关掉——
     * 关掉就等于把别人的下一轮一起杀了。真正的关闭只发生在淘汰 / 停机（{@link SharedAgentPool}）。
     */
    @Override
    public void close(AgentSession session) {
        release(session);
    }

    /** 应用停机：把池子里剩下的 agent 全部关掉（框架静态注册表不留东西）。 */
    public void closeAll() {
        agents.closeAll();
    }

    /**
     * 记下框架拦下来的待确认调用（§19.9）。
     *
     * <p>为什么不在这里自己判断「哪些是写操作」：**框架才是拦截者**，它已经算好了这一批被拦下的
     * 调用（状态 {@code ASKING}）；平台再猜一遍只会猜出第二套口径，两套口径迟早对不上。
     *
     * <p>为什么要去重：同一批待确认调用可能因为重连 / 重放被重复推来，重复记下会让用户点一次确认
     * 却提交了两条对同一次调用的答案（框架侧直接判非法）。
     */
    private void rememberPendingConfirmation(AgentscopeSession session, io.agentscope.core.event.AgentEvent event) {
        if (!(event instanceof RequireUserConfirmEvent confirm)) {
            return;
        }
        List<ToolUseBlock> calls = confirm.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return;
        }
        Set<String> known = session.pendingToolCalls.stream()
                .map(ToolUseBlock::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ToolUseBlock> fresh = calls.stream()
                .filter(call -> call.getId() == null || !known.contains(call.getId()))
                .toList();
        if (!fresh.isEmpty()) {
            registerPendingToolCalls(session, fresh);
            session.setPendingConfirmPrompt(confirmPromptFor(fresh));
        }
    }

    /**
     * 造一张确认卡的抬头（§12.2 的 {@code confirmId / action / summary}）。
     *
     * <p>为什么平台必须自己造：框架的 {@code REQUIRE_USER_CONFIRM} 只说「这几次调用要用户点头」，
     * 它没有、也不该有平台这张卡片的编号。翻译器只搬框架字段，于是事件到了前端长成
     * {@code {"confirmId":"","action":"","summary":""}}——前端回传空编号，接口一律 400，
     * 用户看到的就是「点了确认没反应」（2026-09-27 实测）。
     */
    private static ConfirmPrompt confirmPromptFor(List<ToolUseBlock> toolCalls) {
        List<String> names = toolCalls.stream()
                .map(ToolUseBlock::getName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        String action = names.isEmpty() ? "write" : String.join(",", names);
        String summary = names.isEmpty()
                ? "即将执行一次写操作，需要您确认后才会真正执行"
                : "即将执行写操作：" + String.join("、", names) + "；确认后才会真正执行";
        return new ConfirmPrompt(UUID.randomUUID().toString(), action, summary);
    }

    /**
     * 给「等待确认」事件补上平台那张卡的抬头。
     *
     * <p>挂起的那张卡是**一轮一张**：一轮里出现第二次写调用会再拦一次、再造一张，与
     * 「写操作每轮 ≤3 次、凭据一次性」（§19.9）一致。
     */
    private static AgentEvent withConfirmPrompt(AgentscopeSession session, AgentEvent event) {
        if (event.type() == AgentEventType.CONFIRM_RESULT) {
            return withAnsweredPrompt(session, event);
        }
        ConfirmPrompt prompt = session.pendingConfirmPrompt;
        if (event.type() != AgentEventType.AWAITING_CONFIRM || prompt == null) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("confirmId", prompt.confirmId());
        payload.put("action", prompt.action());
        payload.put("summary", prompt.summary());
        return copyWithPayload(event, payload);
    }

    /**
     * 给「确认结果」事件补上**被答复那张卡**的编号与结论。
     *
     * <p>框架的 {@code USER_CONFIRM_RESULT} 只说「有人答复了」，它既不知道平台那张卡的编号，
     * 也不带批准/拒绝。照搬字段的结果就是事件长成
     * {@code {"confirmId":"","approved":false}}：前端拿着空编号对不回任何一张卡，
     * 也无法把卡片标成「已批准 / 已拒绝」——只能再弹一张新卡，用户就被要求确认第二次
     * （2026-09-27 实测：客户端照着流里的第一张卡去确认，拿到的是空编号，接口一律 400）。
     */
    private static AgentEvent withAnsweredPrompt(AgentscopeSession session, AgentEvent event) {
        ConfirmPrompt answered = session.answeredPrompt;
        if (answered == null) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("confirmId", answered.confirmId());
        payload.put("approved", session.answeredApproved);
        payload.put("action", answered.action());
        return copyWithPayload(event, payload);
    }

    /** 事件只换载荷：编号 / 轮次 / 序号 / 来源一律照旧，回放与落库的定位不能因为补字段而变。 */
    private static AgentEvent copyWithPayload(AgentEvent event, Map<String, Object> payload) {
        return new AgentEvent(
                event.eventId(),
                event.type(),
                event.sessionId(),
                event.turnId(),
                event.traceId(),
                event.seq(),
                event.timestampEpochMs(),
                event.source(),
                payload);
    }

    /** 记录待确认项，并暂存框架确认结果（§19.9 的 HITL 通道）。 */
    void registerPendingToolCalls(AgentSession session, List<ToolUseBlock> toolCalls) {
        ((AgentscopeSession) session).pendingToolCalls.addAll(toolCalls);
    }

    /**
     * 一张确认卡的抬头（§12.2）：{@code confirmId} 让前端能把它原样回传，{@code action / summary}
     * 是给用户看的那一行字。编号由**平台**发，框架不参与——它只负责说「这里要停一下」。
     */
    private record ConfirmPrompt(String confirmId, String action, String summary) {}

    static final class AgentscopeSession implements AgentSession {
        private final String sessionId;
        // 包内可见：契约测试要断言「装出来的 agent 长什么样」（AgentscopeRuntimeBaselineTest）。
        final HarnessAgent agent;
        private final AgentRunRequest request;
        private final List<ToolUseBlock> pendingToolCalls = new ArrayList<>();
        private final List<ConfirmResult> pendingConfirmResults = new ArrayList<>();
        /** 用户批准后登记的一次性凭据：**只给紧随其后的那一轮**用（取一次就清）。 */
        private String confirmIdForTurn;
        /** 当前挂起的那张确认卡（§12.2）；用户答复后清掉——一张卡只对一次拦截负责。 */
        private ConfirmPrompt pendingConfirmPrompt;

        /** 刚答复过的那张卡（编号 + 结论）；仅用于给紧随其后的 CONFIRM_RESULT 事件补编号。 */
        private ConfirmPrompt answeredPrompt;

        private boolean answeredApproved;

        void setPendingConfirmPrompt(ConfirmPrompt prompt) {
            this.pendingConfirmPrompt = prompt;
            if (prompt != null) {
                // 新卡一立，上一张的答复痕迹就没用了：留着会让后面的 CONFIRM_RESULT 认错卡。
                this.answeredPrompt = null;
            }
        }

        /** 记下「刚答复的是哪张卡、结论是什么」，供 {@link #withAnsweredPrompt} 使用。 */
        void markAnswered(ConfirmPrompt prompt, boolean approved) {
            this.answeredPrompt = prompt;
            this.answeredApproved = approved;
        }

        void setConfirmIdForTurn(String confirmId) {
            this.confirmIdForTurn = confirmId;
        }

        String takeConfirmIdForTurn() {
            String taken = confirmIdForTurn;
            confirmIdForTurn = null;
            return taken;
        }

        AgentscopeSession(String sessionId, HarnessAgent agent, AgentRunRequest request) {
            this.sessionId = sessionId;
            this.agent = agent;
            this.request = request;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public String runtimeId() {
            return RUNTIME_ID;
        }

        @Override
        public String runtimeVersion() {
            return RUNTIME_VERSION;
        }
    }

    static Optional<String> emptyIfNull(String value) {
        return Optional.ofNullable(value);
    }
}
