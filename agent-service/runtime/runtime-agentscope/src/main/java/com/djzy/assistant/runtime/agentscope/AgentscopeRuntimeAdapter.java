package com.djzy.assistant.runtime.agentscope;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentRuntimePort;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.ConfirmDecision;
import com.djzy.assistant.spi.RuntimeCapabilities;
import com.djzy.assistant.spi.RuntimeMismatchException;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import com.djzy.assistant.spi.Snapshot;
import com.djzy.assistant.spi.tool.ToolSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
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
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private final SharedAgentPool agents = new SharedAgentPool(MAX_CACHED_AGENTS);

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
        this.modelProvider = modelProvider;
        this.stateStore = stateStore;
        this.workspace = workspace;
        this.workspaceStore = workspaceStore;
        this.skillProvisioner = skillProvisioner == null ? SkillProvisioner.NONE : skillProvisioner;
        this.sandbox = sandbox == null ? SandboxSettings.disabled() : sandbox;
        this.skillStaging = this.sandbox.enabled()
                ? new SandboxSkillStaging(workspace.resolve("sandbox-skills"))
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
        RuntimeContext context = RuntimeContext.builder()
                .userId(request.userId())
                .sessionId(session.sessionId())
                .put(CallAttributes.TURN_ID, turn.turnId())
                .put(CallAttributes.TRACE_ID, turn.traceId())
                .put(CallAttributes.REQUEST_ID, request.requestId())
                .put(CallAttributes.DEADLINE_EPOCH_MS, request.deadlineEpochMs())
                .put(CallAttributes.TOOL_INVOKER, request.toolInvoker())
                .put(CallAttributes.SYSTEM_PROMPT, request.systemPromptPrefix())
                .build();
        // 技能下发：**在提示词拼装之前**把这一轮可见的技能同步进工作区。
        // 为什么每轮都做：技能的可见性来自网关的授权，随时会被收走；而模型每一轮都要看当前这份。
        // 代价可控——没变的技能不会重写（见 WorkspaceSkillProvisioner 的类注释）。
        provisionSkills(agentscope.agent, request, context);
        // 技能投影（H-06b）：容器里也要有一份技能，技能里的脚本才跑得动。
        // 必须排在技能下发**之后**——下发的就是这一轮该有的那份，投影抄的正是它。
        prepareSandbox(agentscope.agent, context);
        Msg message = buildMessage(agentscope, turn);
        return agentscope.agent
                .streamEvents(message, context)
                .mapNotNull(event -> translator.translate(event, session.sessionId(), turn.turnId(), turn.traceId()).orElse(null))
                .onErrorMap(AgentscopeRuntimeAdapter::actionableOrOriginal);
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
    void prepareSandbox(HarnessAgent agent, RuntimeContext ctx) {
        if (!sandbox.enabled() || skillStaging == null) {
            return;
        }
        clearStaleSandboxState(ctx);
        Path stagingRoot = skillStaging.stage(agent.getWorkspaceManager().getFilesystem(), ctx);
        ctx.put(SandboxContext.class, sandbox.toProjectingFilesystemSpec().toSandboxContext(stagingRoot));
    }

    /**
     * 清掉上一轮留下的沙箱状态（容器与沙箱状态都是「一轮一个」，留着反而有害）。
     *
     * <p><b>为什么非清不可</b>：容器在本轮结束时会被框架 stop 掉再删掉
     * （{@code SandboxManager#release}），下一轮是一台**全新的空容器**。而框架把「投影做过一次」
     * 记在了沙箱状态里（内容的哈希），下一轮看到哈希没变就**不再投影**——于是「第一轮容器里跑得动脚本，
     * 第二轮开始文件就不在了」（2026-09-26 真容器实测，见台账 L-20）。状态里其余的字段本来就是死的
     * （容器 id 指向一台已经被删掉的容器）。
     *
     * <p>清掉之后框架按「全新沙箱」处理：起容器 + 应用工作区 + 重新投影，这才是这一轮该有的样子。
     * 注意清的是**沙箱状态**（框架自己的 {@code _sandbox_state} 槽位），不是会话状态——
     * 聊了什么在另一把键上，照样跨实例、跨轮次（见类注释里 A/B/C 那一段）。
     */
    private void clearStaleSandboxState(RuntimeContext ctx) {
        try {
            Optional<SandboxIsolationKey> key = SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_NAME);
            if (key.isEmpty()) {
                return;
            }
            new SessionSandboxStateStore(stateStore, AGENT_NAME).delete(key.get());
        } catch (Exception e) {
            // 清不掉就退回框架的默认行为（第二轮可能看不到技能脚本）：记日志，别让这一轮失败。
            log.warn("上一轮的沙箱状态没清掉（这一轮容器里可能没有技能脚本）：原因={}", e.toString());
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
            cause = cause.getCause();
        }
        return error;
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
        agentscope.pendingConfirmResults.addAll(results);
        agentscope.pendingToolCalls.clear();
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

    /** 记录待确认项，并暂存框架确认结果（§19.9 的 HITL 通道）。 */
    void registerPendingToolCalls(AgentSession session, List<ToolUseBlock> toolCalls) {
        ((AgentscopeSession) session).pendingToolCalls.addAll(toolCalls);
    }

    static final class AgentscopeSession implements AgentSession {
        private final String sessionId;
        // 包内可见：契约测试要断言「装出来的 agent 长什么样」（AgentscopeRuntimeBaselineTest）。
        final HarnessAgent agent;
        private final AgentRunRequest request;
        private final List<ToolUseBlock> pendingToolCalls = new ArrayList<>();
        private final List<ConfirmResult> pendingConfirmResults = new ArrayList<>();

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
