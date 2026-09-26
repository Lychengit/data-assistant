package com.djzy.assistant.runtime.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.util.List;

/**
 * 技能下发钩子（H-06a / DR-41）：把「这个用户可见的技能」变成工作区里的文件。
 *
 * <p><b>为什么是运行时的一个接口、实现却在接入层</b>：技能内容从对象存储来、技能清单从网关来，
 * 这两样只有接入层够得着；而「写进哪个命名空间」只有运行时知道——命名空间是框架按
 * {@link RuntimeContext} 解析的（{@code IsolationScope.USER}）。两边各拿一半，所以在这里定义接口、
 * 由接入层实现（和 {@link ModelProvider} 是同一个套路）。
 *
 * <p><b>为什么由运行时调用而不是接入层直接写</b>：接入层拿不到「agent 此刻用的那个文件系统」，
 * 自己去拼命名空间等于把框架的命名规则抄一遍——框架改一次就悄悄错一次。
 *
 * <p>调用时机是每一轮开始之前（{@code start()}）：技能可能在两轮之间被授权 / 撤销，
 * 而模型的提示词每轮都要看当前这一份。
 */
public interface SkillProvisioner {

    /**
     * 把 {@code visibleSkills} 里那些技能的内容同步进这个用户的工作区。
     *
     * <p>实现必须满足三条：
     * <ul>
     *   <li><b>幂等</b>：没变的技能不重写（否则每轮都在写共享库）；</li>
     *   <li><b>可撤销</b>：上一步可见、这一步不可见的技能，要连同它写下的文件一起清掉；</li>
     *   <li><b>不抛</b>：下发失败不该让整轮对话失败——技能是增强，不是主链路。失败写日志、返回，让这一轮照常跑。</li>
     * </ul>
     *
     * @param workspace 这个用户的工作区文件系统（命名空间已由框架按 ctx 解析好）
     * @param ctx 这一轮的运行时上下文（userId / sessionId）
     * @param visibleSkills 这一轮该用户可见的技能编码（网关判定，可能为空）
     */
    void provision(AbstractFilesystem workspace, RuntimeContext ctx, List<String> visibleSkills);

    /** 不装技能下发（默认）：传它给运行时就等于关掉这个能力。 */
    SkillProvisioner NONE = (workspace, ctx, visibleSkills) -> {};
}
