package com.djzy.assistant.common.confirm;

import java.util.Optional;

/**
 * 待确认项端口（§19.9 / §18.4.5 W1）。
 *
 * <p>确认必须跨服务共享（agent-service 产生、网关消费），因此骨架期就落 PG，不做进程内缓存。
 */
public interface ConfirmStore {

    Optional<ConfirmRecord> find(String confirmId);

    /**
     * 一次性消费：仅当该确认仍为 pending、属于该用户、且未过期时置为 approved。
     *
     * @return true 表示本次消费成功（并发下只有一个调用者能拿到 true）
     */
    boolean consume(String confirmId, String userId);
}
