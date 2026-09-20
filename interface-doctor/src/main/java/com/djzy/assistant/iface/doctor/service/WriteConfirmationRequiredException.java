package com.djzy.assistant.iface.doctor.service;

import com.djzy.assistant.common.error.UnifiedErrors;

/**
 * 写操作缺少确认凭据（W1，§18.4.5 / §19.9）。
 *
 * <p>确认凭据的**权威校验在网关**（一次性消费 {@code pending_confirm}，绑定用户与技能）；
 * 接口服务不查权限库、不回查网关，只做「写操作必须携带凭据」这一道存在性检查（I2），
 * 因此这里只可能因凭据缺失或为空而触发。这道检查由审计切面统一执行，接口自己不用写。
 */
public class WriteConfirmationRequiredException extends RuntimeException {

    private final String httpPath;

    public WriteConfirmationRequiredException(String httpPath) {
        super(UnifiedErrors.FORBIDDEN);
        this.httpPath = httpPath;
    }

    public String httpPath() {
        return httpPath;
    }
}