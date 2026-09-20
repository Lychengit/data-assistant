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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 不得以默认配置对外提供脚本执行。
 */
public final class AgentscopeRuntimeAdapter implements AgentRuntimePort {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeRuntimeAdapter.class);

    public static final String RUNTIME_ID = "agentscope";
    private static final String RUNTIME_VERSION = "2.0.3";

    private final ModelProvider modelProvider;
    private final AgentStateStore stateStore;
    private final Path workspace;

    public AgentscopeRuntimeAdapter(ModelProvider modelProvider, AgentStateStore stateStore, Path workspace) {
        this.modelProvider = modelProvider;
        this.stateStore = stateStore;
        this.workspace = workspace;
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
        return new AgentscopeSession(request.sessionId(), buildAgent(request), request);
    }

    private HarnessAgent buildAgent(AgentRunRequest request) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("doctor-data-assistant")
                .model(modelProvider.modelFor(request))
                .maxIters(request.maxIters())
                .stateStore(stateStore)
                // 工作目录钉在平台 state-dir 下：HarnessAgent 默认按 cwd 建 .agentscope/，
                // 实测会把会话原文与「记忆账本」写进应用启动目录（仓库根），既是数据外泄也是仓库污染。
                .workspace(workspace)
                .disableFilesystemTools()
                .disableShellTool()
                // 记忆类能力一律关闭（ADR-31 基线）：memory tools 会改模型上下文，
                // memory hooks / compaction / 结果驱逐会**每轮偷偷多调模型**（实测每轮 2 次调用），
                // 这些调用既不在平台成本上报里、也不在事件流里，属于「看不见的动作」。
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableCompaction()
                .disableToolResultEviction()
                // 平台事件日志才是唯一事实源（§19.6）：运行时不得再私存一份 transcript / session。
                .disableTranscript()
                .disableSessionPersistence()
                .disableWorkspaceContext()
                // 技能与子代理只能来自平台已审的注册表（§M3），不许运行时按目录自行发现。
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills();
        if (!request.systemPromptPrefix().isBlank()) {
            builder.sysPrompt(request.systemPromptPrefix());
        }
        HarnessAgent agent = builder.build();
        Toolkit toolkit = agent.getToolkit();
        Set<String> platformToolNames = new java.util.LinkedHashSet<>();
        for (ToolSpec spec : request.tools().all()) {
            toolkit.registerAgentTool(new PlatformToolAdapter(spec, request, request.requestId()));
            platformToolNames.add(spec.name());
        }
        // 工具面白名单：HarnessAgent 会**无条件**塞进 web_search / web_fetch / wait_async_results
        // 等内建工具（实测 builder 上没有任何开关能关掉它们）。这些工具绕开平台的 ToolInvoker，
        // 于是也就绕开了 DENY 判定、确认闸口与审计（§18.11.3），对医生助手还是一条
        // 「把提示词里的患者信息发到公网」的现成通道。
        // 规则取最强的一条：**平台没注册的，一律移掉**——这样将来框架升级偷偷加工具也进不来。
        for (String foreign : new java.util.ArrayList<>(toolkit.getToolNames())) {
            if (!platformToolNames.contains(foreign)) {
                toolkit.removeTool(foreign);
                log.info("已移除运行时内建工具（不在平台工具清单内）：{}", foreign);
            }
        }
        return agent;
    }

    @Override
    public Flux<AgentEvent> stream(AgentSession session, AgentTurn turn) {
        AgentscopeSession agentscope = (AgentscopeSession) session;
        AgentscopeEventTranslator translator = new AgentscopeEventTranslator();
        RuntimeContext context = RuntimeContext.builder()
                .userId(agentscope.request.userId())
                .sessionId(session.sessionId())
                .put("turnId", turn.turnId())
                .put("traceId", turn.traceId())
                .put("requestId", agentscope.request.requestId())
                .build();
        Msg message = buildMessage(agentscope, turn);
        return agentscope.agent
                .streamEvents(message, context)
                .mapNotNull(event -> translator.translate(event, session.sessionId(), turn.turnId(), turn.traceId()).orElse(null))
                .onErrorMap(AgentscopeRuntimeAdapter::actionableOrOriginal);
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
     * 提问 + 平台附上的「本轮上下文快照」。
     *
     * <p>快照为什么贴在这里、而不只在系统提示词里：老会话的历史紧挨着新提问，模型会照抄自己上一轮
     * 关于能力的说法（实测见 {@link AgentTurn#ATTR_CONTEXT_REMINDER}）。贴到问题之后是唯一"比历史更近"的位置。
     * 只影响送给模型的消息；平台事件日志里记的仍是用户原文（§19.6 事实源不受影响）。
     */
    static String withContextReminder(String userText, Map<String, Object> attributes) {
        String text = userText == null ? "" : userText;
        Object reminder = attributes == null ? null : attributes.get(AgentTurn.ATTR_CONTEXT_REMINDER);
        String block = reminder == null ? "" : String.valueOf(reminder).trim();
        return block.isEmpty() ? text : text + "\n\n" + block;
    }

    private static Msg buildMessage(AgentscopeSession session, AgentTurn turn) {
        UserMessage.Builder builder = UserMessage.builder()
                .textContent(withContextReminder(turn.userText(), turn.attributes()));
        if (!session.pendingConfirmResults.isEmpty()) {
            builder.metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.copyOf(session.pendingConfirmResults)));
            session.pendingConfirmResults.clear();
        }
        return builder.build();
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

    @Override
    public void cancel(AgentSession session, String reason) {
        ((AgentscopeSession) session).agent.interrupt();
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
        // 状态由 AgentStateStore 按 (userId, sessionId) 寻址；重建 agent 即恢复（§19.5）。
        return new AgentscopeSession(sessionId, buildAgent(request), request);
    }

    @Override
    public void close(AgentSession session) {
        ((AgentscopeSession) session).agent.close();
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
