package com.djzy.assistant.gateway.core;

/**
 * 用户级限速端口（§18.4.2 G4 / §9.3）：同一个用户每秒最多允许几次数据调用。
 *
 * <p>为什么要有这个端口：多副本部署时，如果每个网关各数各的，用户换个副本就等于又拿到一整份额度，
 * 实际上限变成「副本数 × 配置的 qps」，口径失真。规格 §2.3 写得很硬——**计数一律放 Redis，
 * 不许本地内存计数**。于是把它抽成端口：Redis 实现是多副本默认，内存实现只在单副本（本机开发）时显式选。
 *
 * <p>两套实现对外语义必须一致（每秒放行几个），差别只在「这份计数是所有副本共享，还是只有自己看得到」。
 */
public interface UserRateLimiter {

    /** @return true = 放行；false = 这一秒超额，调用方回 429 统一措辞 */
    boolean tryAcquire(String key);
}