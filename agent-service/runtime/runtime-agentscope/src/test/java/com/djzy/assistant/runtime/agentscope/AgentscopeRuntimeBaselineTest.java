package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.AgentTurn;
import com.djzy.assistant.spi.AgentSession;
import com.djzy.assistant.spi.RuntimeMisconfiguredException;
import io.agentscope.core.message.ContentBlock;
import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolSpec;
import io.agentscope.core.model.ModelHttpException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 默认运行时的**能力面**回归（ADR-31 基线 / §18.11.3 / §19.6 / §20.1.6）。
 *
 * <p>这一组断言守的是几件「不写就一定会悄悄坏掉」的事，都是实测踩出来的：
 *
 * <ol>
 *   <li><b>工具面必须等于平台工具面</b>：{@code HarnessAgent} 会**无条件**注册
 *       {@code web_search} / {@code web_fetch} / {@code wait_async_results}（builder 上没有任何开关
 *       能关掉它们），它们绕开平台的 {@code ToolInvoker}，也就绕开 DENY、确认闸口与审计；
 *       而且平台没配工具时它们就成了模型唯一能调用的东西。
 *   <li><b>记忆/压缩钩子必须关</b>：默认开启时每轮会**多调一次模型**（实测每轮 2 次调用），
 *       这些调用不在平台成本上报里、也不在事件流里。
 *   <li><b>工作目录必须钉在平台 workspace-dir 下</b>：默认按 cwd 建 {@code .agentscope/}，
 *       实测会把会话原文与记忆账本写进应用启动目录（仓库根）。
 *   <li><b>模型侧的 HTTP 失败要翻译成「去哪儿改」</b>：401/403/404 是运维改一个字段就能好的事，
 *       不能和「服务故障」共用一句「请稍后再试」；同时一个字的上游回包都不许带出去。
 * </ol>
 */
class AgentscopeRuntimeBaselineTest {

    /** 模型工厂：本测试不跑推理，只验「装出来的 agent 长什么样」。 */
    private static final ModelProvider NO_MODEL = request -> null;

    private static final String UPSTREAM_BODY = "upstream-echoed-secret-payload";

    private static AgentRunRequest request(ToolCatalog catalog) {
        return AgentRunRequest.builder()
                .userId("u-1")
                .sessionId("s-1")
                .requestId("r-1")
                .tools(catalog)
                .toolInvoker(invocation -> null)
                .deadlineEpochMs(System.currentTimeMillis() + 10_000)
                .maxIters(5)
                .build();
    }

    private static AgentscopeRuntimeAdapter.AgentscopeSession session(
            AgentscopeRuntimeAdapter adapter, ToolCatalog catalog) {
        AgentSession started = adapter.start(request(catalog));
        return (AgentscopeRuntimeAdapter.AgentscopeSession) started;
    }

    @Test
    void toolFaceIsExactlyThePlatformToolFace(@TempDir Path workspace) {
        AgentscopeRuntimeAdapter adapter = new AgentscopeRuntimeAdapter(NO_MODEL, null, workspace);
        ToolCatalog catalog = ToolCatalog.of(List.of(
                ToolSpec.read("iface_patient_visits", "就诊记录", Map.of("type", "object"), ToolCategory.IFACE),
                new ToolSpec(
                        "skill_summary", "汇总", Map.of("type", "object"), SideEffect.READ, ToolCategory.SKILL, Set.of())));

        List<String> face = session(adapter, catalog).agent.getToolkit().getToolNames().stream().sorted().toList();

        assertEquals(List.of("iface_patient_visits", "skill_summary"), face);
    }

    @Test
    void emptyPlatformCatalogMeansNoToolsAtAll(@TempDir Path workspace) {
        AgentscopeRuntimeAdapter adapter = new AgentscopeRuntimeAdapter(NO_MODEL, null, workspace);

        List<String> face =
                session(adapter, ToolCatalog.empty()).agent.getToolkit().getToolNames().stream().sorted().toList();

        assertTrue(face.isEmpty(), () -> "平台没配工具时运行时不得自带工具，实际剩：" + face);
    }

    @Test
    void memoryAndCompactionHooksAreOff(@TempDir Path workspace) {
        AgentscopeRuntimeAdapter adapter = new AgentscopeRuntimeAdapter(NO_MODEL, null, workspace);

        var agent = session(adapter, ToolCatalog.empty()).agent;

        assertNull(agent.getCompactionHook(), "压缩钩子必须关闭：它每轮会偷偷多调一次模型");
        assertTrue(agent.getSkillRepositories().isEmpty(), "技能仓库必须为空：技能只来自平台注册表（§M3）");
    }

    @Test
    void workspaceIsPinnedToTheConfiguredDirectory(@TempDir Path workspace) {
        AgentscopeRuntimeAdapter adapter = new AgentscopeRuntimeAdapter(NO_MODEL, null, workspace);

        Path actual = session(adapter, ToolCatalog.empty()).agent.getWorkspaceManager().getWorkspace();

        assertEquals(workspace.toAbsolutePath(), actual.toAbsolutePath(), "工作目录不得默认落到进程 cwd");
    }

    @Test
    void modelSideAuthFailureIsTranslatedIntoActionableText() {
        Throwable mapped = AgentscopeRuntimeAdapter.actionableOrOriginal(new WrappedHttpFailure(401));

        assertInstanceOf(RuntimeMisconfiguredException.class, mapped);
        String message = ((RuntimeMisconfiguredException) mapped).userMessage();
        assertTrue(message.contains("401"), message);
        assertTrue(message.contains("模型供应商"), message);
        assertFalse(message.contains(UPSTREAM_BODY), message);
    }

    @Test
    void otherConfigShapedStatusesAreTranslatedToo() {
        for (int status : new int[] {400, 403, 404, 422}) {
            Throwable mapped = AgentscopeRuntimeAdapter.actionableOrOriginal(new WrappedHttpFailure(status));
            assertInstanceOf(RuntimeMisconfiguredException.class, mapped, "status=" + status);
            assertTrue(((RuntimeMisconfiguredException) mapped).userMessage().contains("模型供应商"), "status=" + status);
        }
    }

    @Test
    void unrecognisedFailuresPassThroughUntouched() {
        RuntimeException plain = new RuntimeException("boom");
        assertSame(plain, AgentscopeRuntimeAdapter.actionableOrOriginal(plain));
        RuntimeException wrapper = new RuntimeException("wrapped", plain);
        assertSame(wrapper, AgentscopeRuntimeAdapter.actionableOrOriginal(wrapper), "认不出来就原样透传，不要改写链路");

        // 限流是**暂时性**故障，不是「配错了」：不能说成去管理端改配置
        Throwable rateLimited = AgentscopeRuntimeAdapter.actionableOrOriginal(new WrappedHttpFailure(429));
        assertInstanceOf(WrappedHttpFailure.class, rateLimited);
    }

    /**
     * 平台附的「本轮能力快照」要落在**提问之后**，而且要能被模型看见。
     *
     * <p>为什么值得一条用例：这段文本是给模型的，位置本身就是它的作用——写在系统提示词里不够
     * （开头离提问远，老会话的历史更近），只有拼在用户消息里才"比历史更近"。
     * 拼错了（比如忘了拼、或拼到前面去）不会报错，只会让模型继续照抄历史里的旧能力。
     *
     * <p>**它同时也守着历史会话的正确性**：历史是从会话状态投影出来的，投影按
     * 「第一个文本块 = 用户原话」取值。如果哪天有人图省事把两者拼回一整段字符串，
     * 这条用例会红——不然用户的气泡里就会莫名其妙多出一段给模型看的快照。
     */
    @Test
    void contextReminderIsAppendedAfterTheUserQuestion() {
        List<ContentBlock> blocks = AgentscopeRuntimeAdapter.contentFor(
                "你有哪些能力", Map.of(AgentTurn.ATTR_CONTEXT_REMINDER, "(本轮能力快照：本轮可用接口 = iface_doctor_a。)"));

        org.junit.jupiter.api.Assertions.assertEquals(2, blocks.size(), "提问一块、快照一块");
        // 第一块是用户原话，**一字不多**（历史投影就靠这条规则取值）
        org.junit.jupiter.api.Assertions.assertEquals("你有哪些能力", textOf(blocks.get(0)));
        // 第二块是快照，且以换行开头：拼起来后模型看到的整段文字与改造前逐字一致
        org.junit.jupiter.api.Assertions.assertEquals("\n(本轮能力快照：本轮可用接口 = iface_doctor_a。)", textOf(blocks.get(1)));
        org.junit.jupiter.api.Assertions.assertEquals(
                "你有哪些能力\n\n(本轮能力快照：本轮可用接口 = iface_doctor_a。)",
                blocks.stream().map(AgentscopeRuntimeBaselineTest::textOf).collect(java.util.stream.Collectors.joining("\n")));
    }

    /** 没有快照（例如续跑那一轮没带）时不许凭空多出空行或括号：模型看到的是干净原文。 */
    @Test
    void noReminderMeansTheQuestionIsPassedThroughUnchanged() {
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("上个月各科室门诊量"),
                texts(AgentscopeRuntimeAdapter.contentFor("上个月各科室门诊量", Map.of())));
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("上个月各科室门诊量"),
                texts(AgentscopeRuntimeAdapter.contentFor("上个月各科室门诊量", null)));
    }

    private static List<String> texts(List<ContentBlock> blocks) {
        return blocks.stream().map(AgentscopeRuntimeBaselineTest::textOf).toList();
    }

    private static String textOf(ContentBlock block) {
        return ((io.agentscope.core.message.TextBlock) block).getText();
    }

    private static final class WrappedHttpFailure extends RuntimeException implements ModelHttpException {

        private final Integer statusCode;

        WrappedHttpFailure(int statusCode) {
            super("HTTP transport error: " + UPSTREAM_BODY);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }
}
