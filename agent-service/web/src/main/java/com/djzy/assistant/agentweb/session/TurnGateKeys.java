package com.djzy.assistant.agentweb.session;

/**
 * 轮次闸门的键怎么拼（T1-07）。
 *
 * <p>框架的闸门接口只认一个字符串键（{@code SessionTurnGate.acquire(key)}），
 * 而平台这边天然是「用户 + 会话」两维。把这两维拼成同一个键收在这一个地方：
 * 抢坑位的人、判断「坑里有没有人」的人如果各拼一次，迟早有一边改歪，
 * 表现成「明明有人在跑，却判断成没人」——那正是这道闸门要防的事。
 */
public final class TurnGateKeys {

    private TurnGateKeys() {}

    /** 一个会话一把键：同一用户的不同会话互不相干，不同用户的同名会话也不相干。 */
    public static String of(String userId, String sessionId) {
        return userId + ':' + sessionId;
    }
}
