package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.PendingConfirmationException;
import org.junit.jupiter.api.Test;

/**
 * 「还有一张写操作确认卡没答复」必须被翻译成**能照着做**的话（§19.9 / §20.1.6）。
 *
 * <p>实测（2026-09-27）：会话里挂着一张没答复的确认卡时再提问，框架抛
 * {@code IllegalStateException("Agent is paused for human-in-the-loop confirmation…")}，
 * 接入层兜底把它归一化成 {@code INTERNAL} + 「服务暂不可用，请稍后再试」——用户看到的是平台故障，
 * 实际是自己少点了一次「确认」，重试永远不会好。
 */
class PendingConfirmationMappingTest {

    @Test
    void 框架的待确认异常_翻译成能照着做的中文() {
        Throwable raw = new IllegalStateException(
                "Agent is paused for human-in-the-loop confirmation: the following tool call(s) are in"
                        + " ASKING state and need your approval before the agent can continue:"
                        + " [iface_doctor_export_upload (id=call_1)]");

        Throwable mapped = AgentscopeRuntimeAdapter.actionableOrOriginal(raw);

        PendingConfirmationException pending = assertInstanceOf(PendingConfirmationException.class, mapped);
        assertEquals(PendingConfirmationException.DEFAULT_USER_MESSAGE, pending.userMessage(), "下发前端的必须是常量文本，不带任何内部细节");
        assertTrue(pending.userMessage().contains("确认"), "要让用户知道自己该点哪一步");
    }

    @Test
    void 包在_cause_里也算数() {
        Throwable raw = new IllegalStateException(
                "外层包装",
                new IllegalStateException("Agent is paused for human-in-the-loop confirmation: [x]"));

        assertInstanceOf(PendingConfirmationException.class, AgentscopeRuntimeAdapter.actionableOrOriginal(raw));
    }

    @Test
    void 别的_IllegalStateException_保持原样_不许误判() {
        IllegalStateException other = new IllegalStateException("会话状态损坏");

        assertSame(other, AgentscopeRuntimeAdapter.actionableOrOriginal(other), "认不出来只会回到原措辞，不能把别的失败当成待确认");
    }
}
