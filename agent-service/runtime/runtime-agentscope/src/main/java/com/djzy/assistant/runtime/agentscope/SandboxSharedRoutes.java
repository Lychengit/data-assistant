package com.djzy.assistant.runtime.agentscope;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱模式下的「共享路径」路由表（H-05 / DR-42）：**哪些工作区路径仍然读共享库，而不是容器**。
 *
 * <p>沙箱当主之后，工作区默认全在容器里——多实例就散了（甲机器容器里写的技能，乙机器看不见）。
 * 所以要把「必须跨实例看到同一份」的那些路径，一条条从容器里摘出来交回共享库，这就是路由。
 *
 * <p><b>为什么不在这里自己拼前缀和命名空间</b>：远端模式（H-04）用的是框架的
 * {@link RemoteFilesystemSpec}，它内部已经把「前缀列表 + 命名空间（{@code agents/<id>/users/<uid>/<段>}）
 * + 上层共享库下层只读模板」这套装配做好了。这里直接**问它要**每个前缀对应的那个文件系统，
 * 于是两种模式下同一个文件在共享库里的键位**逐字相同**——切开关不会"技能突然全没了"，
 * 这是复用而不是抄一遍的全部理由。
 *
 * <p>代价是依赖「它建出来的确实是 {@link CompositeFilesystem}」这一点：框架哪天换了装配，
 * 这里会在**构造 agent 时**当场抛错（而不是悄悄退化成"整块工作区都在容器里"）。
 */
final class SandboxSharedRoutes {

    private SandboxSharedRoutes() {}

    /**
     * 取「这些前缀各自对应的共享文件系统」。
     *
     * @param store 共享库（工作区那套，见 {@code PlatformWorkspaceStore}）
     * @param hostWorkspace 本机工作目录：只用来给框架的"只读模板层"当根，沙箱模式下不参与读
     * @param agentId agent 名（必须与 {@code HarnessAgent.Builder.name(...)} 一致：命名空间里带它）
     * @param scope 隔离粒度，必须与远端模式一致（USER = 一个用户一份）
     * @param prefixes 要路由的前缀（目录边界写法）
     * @return 前缀 → 共享文件系统；交给 {@code Builder.filesystemRoute(前缀, 文件系统)}
     */
    static Map<String, AbstractFilesystem> build(
            BaseStore store,
            Path hostWorkspace,
            String agentId,
            IsolationScope scope,
            List<String> prefixes) {
        AbstractFilesystem shared = new RemoteFilesystemSpec(store)
                .isolationScope(scope)
                .toFilesystem(hostWorkspace, agentId, scope.toNamespaceFactory());
        if (!(shared instanceof CompositeFilesystem composite)) {
            throw new IllegalStateException("远端文件系统不再是 CompositeFilesystem（实际是 "
                    + shared.getClass().getName() + "）：无法复用它的共享路径，请重新核对 H-05 的装配");
        }
        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        for (String prefix : prefixes) {
            String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
            routes.put(normalized, composite.filesystemFor(normalized));
        }
        return routes;
    }
}
