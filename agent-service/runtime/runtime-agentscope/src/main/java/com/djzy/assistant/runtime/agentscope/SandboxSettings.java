package com.djzy.assistant.runtime.agentscope;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import java.util.List;

/**
 * 沙箱设置（H-05）：**要不要把命令执行放到容器里跑、容器长什么样**。
 *
 * <p>它是接入层配置（{@code agent-service.sandbox.*}）在运行时里的样子——接入层只负责读配置、
 * 这里负责把它翻译成框架认的装配对象。两边分开的好处：运行时不必认识 Spring 的配置类，
 * 配置校验只有一处（见 {@code AgentServiceConfig}）。
 *
 * <p><b>默认是关的</b>（{@link #disabled()}）：关着的时候运行时的行为与改造前逐字一致——
 * 没有沙箱、没有壳工具，模型能用的只有平台自己注册的那些工具（ADR-31 基线）。
 *
 * <p><b>开着的时候会发生什么</b>（四件事，缺一不可）：
 * <ol>
 *   <li><b>容器变成 agent 的主文件系统</b>：命令与临时文件都在容器里，模型跑脚本不再碰宿主机；</li>
 *   <li><b>共享的那些工作区前缀照旧走共享库</b>（默认 {@code skills/}，见 {@link #sharedPrefixes}）：
 *       容器只是执行场所，技能正文这类**要跨实例看到同一份**的内容仍然在共享库里，由路由分流；</li>
 *   <li><b>技能被投影进容器</b>（H-06b，见 {@link #toProjectingFilesystemSpec()}）：路由让模型**读**得到
 *       技能，投影让容器**跑**得动技能里的脚本——框架的提示词已经把技能目录写成了容器里的路径
 *       （{@code /workspace/skills/<技能>}），容器里就必须真有这份文件，否则模型会照着一个不存在的路径去跑脚本；</li>
 *   <li><b>壳工具被放行</b>：沙箱里没有壳就等于白开（这是开关打开后唯一故意偏离 ADR-31 基线的地方，
 *       所以它必须是显式打开的，见 {@link #enabled()}）。</li>
 * </ol>
 *
 * <p><b>还有一件不在这四件事里、但开着沙箱就默认打开的东西</b>：<b>跨实例执行锁</b>（H-11，见 {@link #executionGuard}）。
 * 隔离槽是按用户分的，同一个用户的两轮几乎同时开始时会各起一个容器、后写的状态覆盖先写的（台账 L-21 有实测记录）——
 * 所以开着沙箱时默认上一把「按用户的锁」：进沙箱前拿、整轮跑完松开。不想要（比如单实例试跑）可以用
 * {@code agent-service.sandbox.distributed-lock=false} 关掉。
 *
 * <p><b>为什么路由前缀要写死成一小撮、而不是"整个工作区一把梭"</b>：框架的路由按前缀逐条匹配，
 * 而且要按目录边界写（{@code skills/}），写成 {@code /} 一条都匹配不上（实测，见台账 L-15）。
 *
 * @param enabled 是否启用容器沙箱（默认 false）
 * @param image 容器镜像；镜像不存在时框架会自己拉（{@code docker run} 的行为）
 * @param workspaceRoot 容器里的工作区根目录（框架默认 {@code /workspace}）
 * @param memoryBytes 单容器内存上限（字节）；null = 不限制
 * @param cpuCount 单容器 CPU 上限；null = 不限制
 * @param network 容器接入的网络名；null/空 = Docker 默认
 * @param sharedPrefixes 继续走共享库的工作区前缀（目录边界写法，默认只路由 {@code skills/}）
 * @param executionGuard 跨实例执行锁（H-11）：同一个用户同时只允许一轮在容器里跑；
 *     {@code null} = 不锁（框架默认的 noop）。关着沙箱时一律为 null，见下方归一化
 */
public record SandboxSettings(
        boolean enabled,
        String image,
        String workspaceRoot,
        Long memoryBytes,
        Long cpuCount,
        String network,
        List<String> sharedPrefixes,
        SandboxExecutionGuard executionGuard) {

    /** 默认镜像：够小、够通用，且一定拉得到（本机实测过）。 */
    public static final String DEFAULT_IMAGE = "ubuntu:22.04";

    /** 框架自己的默认工作区根目录（{@code SandboxClientOptions#getWorkspaceRoot}）。 */
    public static final String DEFAULT_WORKSPACE_ROOT = "/workspace";

    /**
     * 默认要路由回共享库的前缀。
     *
     * <p>只有 {@code skills/} 一项，因为它是目前**唯一**平台会写进工作区、且必须跨实例看到同一份的东西
     * （H-06a 的技能下发）。以后有别的共享内容（知识库、约定文件）时在这里加一行即可——
     * 但必须是**目录边界**写法（带结尾斜杠），否则会连 {@code skillsX/} 这种同前缀目录一起吞掉。
     */
    public static final List<String> DEFAULT_SHARED_PREFIXES = List.of("skills/");

    private static final SandboxSettings OFF = new SandboxSettings(
            false, DEFAULT_IMAGE, DEFAULT_WORKSPACE_ROOT, null, null, null, DEFAULT_SHARED_PREFIXES, null);

    /** 归一化空值：配置里留空的项目按默认值处理，省得每个用到它的地方都判一次。 */
    public SandboxSettings {
        image = (image == null || image.isBlank()) ? DEFAULT_IMAGE : image;
        workspaceRoot = (workspaceRoot == null || workspaceRoot.isBlank())
                ? DEFAULT_WORKSPACE_ROOT
                : workspaceRoot;
        sharedPrefixes = (sharedPrefixes == null || sharedPrefixes.isEmpty())
                ? DEFAULT_SHARED_PREFIXES
                : List.copyOf(sharedPrefixes);
        // 沙箱关着的时候锁没有意义（压根不会有容器），统一按「不锁」处理：
        // 免得出现「配置里写着关沙箱、却因为传了个锁而占着一条数据库连接」这种说不清的状态。
        executionGuard = enabled ? executionGuard : null;
    }

    /** 不启用沙箱（默认）：执行面不存在，行为与改造前一致。 */
    public static SandboxSettings disabled() {
        return OFF;
    }

    /**
     * 翻译成框架的 Docker 沙箱装配对象。
     *
     * <p>三处刻意的选择：
     * <ul>
     *   <li><b>关掉框架的默认投影</b>（{@code workspaceProjectionEnabled(false)}）：那套的做法是
     *       「把**这台实例**的工作目录打 tar 塞进容器」，多副本下每台机器的目录内容都不一样，
     *       属于来源不确定；要进容器的内容应该由平台明确给——每轮那条路见
     *       {@link #toProjectingFilesystemSpec()}（这里不投影，是因为这个 spec 只用来建
     *       agent 的默认上下文，而真正每次调用用的那个会带上投影，见 {@link AgentscopeRuntimeAdapter}）。</li>
     *   <li><b>自带一个只有根目录的 WorkspaceSpec</b>：框架默认的那个根是 {@code /workspace}，
     *       这里交给我们自己的配置值，免得"配置里写了根目录、实际却不是那个"。</li>
     *   <li><b>快照用框架默认（noop）</b>：容器的工作区**不需要**跨实例持久化——要共享的内容在共享库里，
     *       容器里只有临时文件。真要给容器做快照（保存中间产物）时，那是另一件事（台账 H-10）。</li>
     *   <li><b>显式写死隔离粒度 USER</b>：框架对"没设"的处理是当成 USER（{@code SandboxIsolationKey.resolve}），
     *       但这件事必须写在明面上——它决定"一个用户一个容器"还是"所有人共用一个容器"，
     *       靠框架的兜底值来保证多租户隔离太脆，所以这里显式给上，用例也钉住它。</li>
     * </ul>
     */
    public DockerFilesystemSpec toFilesystemSpec() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec().image(image).workspaceRoot(workspaceRoot);
        if (memoryBytes != null) {
            spec.memorySizeBytes(memoryBytes);
        }
        if (cpuCount != null) {
            spec.cpuCount(cpuCount);
        }
        if (network != null && !network.isBlank()) {
            spec.network(network);
        }
        spec.isolationScope(IsolationScope.USER);
        spec.workspaceProjectionEnabled(false);
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot(workspaceRoot);
        spec.workspaceSpec(workspace);
        // 跨实例执行锁（H-11）：交给框架，它会在 acquire 之前拿锁、release 之后再松开。
        // 不设的话框架就是 noop（不锁），同一个用户的两轮并发会各起一个容器、互相覆盖状态。
        if (executionGuard != null) {
            spec.executionGuard(executionGuard);
        }
        return spec;
    }

    /**
     * 翻译成「**带技能投影**」的沙箱装配对象：容器里也要有一份技能，技能里的脚本才能在容器里跑（H-06b）。
     *
     * <p>投影的来源由平台给：调用方把「这个用户本轮的技能暂存目录」交给
     * {@code spec.toSandboxContext(暂存目录)}，框架启动容器时就把那个目录下的 {@code skills/}
     * 打 tar 灌进容器——于是容器里的 {@code /workspace/skills/<技能>/} 与共享库里的那份同内容，
     * 而框架提示词里给模型看的 {@code <files-root>} 正好就是 {@code /workspace/skills/<技能>}
     * （见框架的 {@code ShellPathPolicy}）。两处对得上，模型才真的能把脚本跑起来。
     *
     * <p><b>只投影技能目录</b>：容器要的是「能跑的脚本」，工作区里别的东西（比如下发的索引文件）
     * 没有进容器的理由；真需要别的共享内容时，往这里加一条 {@code includeRoots} 就行（与
     * {@link #sharedPrefixes()} 一样，要写目录边界）。
     *
     * <p>为什么每轮都要新建一个 spec：投影源是**每个用户、每一轮**都可能不一样的本地目录，
     * 而这个 spec 里就写着投影源，所以它没法在装配 agent 时定死（见
     * {@link AgentscopeRuntimeAdapter} 里每轮调用前那一段）。
     */
    public DockerFilesystemSpec toProjectingFilesystemSpec() {
        DockerFilesystemSpec spec = toFilesystemSpec();
        spec.workspaceProjectionEnabled(true);
        spec.workspaceProjectionRoots(List.of(SandboxSkillStaging.SKILLS_DIR));
        return spec;
    }

    /** 日志用的一行摘要（启动时打一次，方便对照"这台实例到底开没开沙箱"）。 */
    public String describe() {
        if (!enabled) {
            return "关闭（命令不在容器里跑：没有沙箱、没有壳工具）";
        }
        return "开（镜像 " + image + "，工作区 " + workspaceRoot + "，共享前缀 " + sharedPrefixes
                + "，跨实例执行锁 " + (executionGuard == null ? "关" : "开") + "）";
    }
}
