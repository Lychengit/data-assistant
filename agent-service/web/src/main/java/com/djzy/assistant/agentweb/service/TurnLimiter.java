package com.djzy.assistant.agentweb.service;

import java.util.Map;

/**
 * 用户级轮次配额（§9.3 限流与配额）。
 *
 * <p>为什么要有这个端口：§2.3 写得很硬——**限流、用量、并发等计数一律放 Redis，不许本地内存计数**
 * （多副本各算一份，口径就失真了）。但「只有 PG 也得跑」的骨架期又需要一个不依赖中间件的退路，
 * 于是把它抽成端口，由装配决定用哪个实现；语义（每分钟可发起的轮次数）两套必须一致。
 *
 * <p>限流只挡「发起」，不影响已建立连接的回放——重连本质是读历史，不消耗模型预算。
 */
public interface TurnLimiter {

    /** @return true = 放行；false = 这一分钟的超额，调用方回 429 统一措辞 */
    boolean tryAcquire(String userId);

    /** 观测用：本实例看得到的窗口用量（Redis 实现只读得到自己刚碰过的那些用户，不必强求全量）。 */
    default Map<String, Integer> windows() {
        return Map.of();
    }
}