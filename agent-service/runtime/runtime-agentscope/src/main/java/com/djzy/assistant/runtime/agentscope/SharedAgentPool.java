package com.djzy.assistant.runtime.agentscope;

import io.agentscope.harness.agent.HarnessAgent;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 共享 agent 池：一把键一个 {@link HarnessAgent}，满了就淘汰最久没用的那个。
 *
 * <p>**为什么不是「一个全局单例」**：agent 身上有几样东西是「按实例固定」的——注册在 Toolkit 上的
 * 工具面、模型客户端、迭代上限。不同用户能调的接口不同，工具面就不同，硬塞进一个实例只有两种下场：
 * 要么把所有人的工具并起来（谁都能调别人的接口，越权），要么每轮改 Toolkit（框架的
 * {@code registerAgentTool} / {@code removeTool} 会改到并发中的别的调用，且 {@code syncToolkitToState}
 * 会把**全局**的激活组写回**本会话**的状态，并发下会把别人的工具面写进你的会话）。
 * 所以共享的粒度是「**同一工具面**」：键相同才共享，键不同各用各的，互不影响。
 *
 * <p>**为什么必须有上限**：管理端换一次模型、加一批接口就多一把键，工厂（{@code build()}）不设上限
 * 就是内存泄漏——而且框架会把 agent 注册进静态的 {@code GracefulShutdownManager}，
 * 不 {@code close()} 掉会一直挂着。这里满了就淘汰并关掉，闭着眼也不会漏。
 *
 * <p>键相同但并发第一次到达时，只让一个线程去建（建一次很贵：技能扫描、tools.json、十几个中间件），
 * 其余线程等它建好直接用。所以 {@code get} 是 {@code synchronized} 的。
 */
final class SharedAgentPool {

    private static final Logger log = LoggerFactory.getLogger(SharedAgentPool.class);

    private final int maxSize;

    /** accessOrder = true：每次 get 都把条目挪到队尾，淘汰时从队头拿（LRU）。 */
    private final LinkedHashMap<String, HarnessAgent> agents = new LinkedHashMap<>(16, 0.75f, true);

    SharedAgentPool(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    /** 取（没有就建）。同一把键并发调用只会建一个。 */
    synchronized HarnessAgent get(String key, Supplier<HarnessAgent> factory) {
        HarnessAgent existing = agents.get(key);
        if (existing != null) {
            return existing;
        }
        HarnessAgent created = factory.get();
        agents.put(key, created);
        evictOverflow();
        return created;
    }

    /** 目前缓存了几个（测试与运维自检用）。 */
    synchronized int size() {
        return agents.size();
    }

    /** 关掉全部（应用停机时调用；框架的静态注册表里不留东西）。 */
    synchronized void closeAll() {
        for (HarnessAgent agent : agents.values()) {
            closeQuietly(agent);
        }
        agents.clear();
    }

    private void evictOverflow() {
        Iterator<Map.Entry<String, HarnessAgent>> it = agents.entrySet().iterator();
        while (agents.size() > maxSize && it.hasNext()) {
            Map.Entry<String, HarnessAgent> eldest = it.next();
            it.remove();
            closeQuietly(eldest.getValue());
        }
    }

    /** 关不掉的 agent 只记一条日志：淘汰是清理动作，不该让调用方因为清理失败而失败。 */
    private static void closeQuietly(HarnessAgent agent) {
        try {
            agent.close();
        } catch (RuntimeException e) {
            log.warn("关闭被淘汰的共享 agent 失败（不影响本次调用）", e);
        }
    }
}
