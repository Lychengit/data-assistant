package com.djzy.assistant.management.service;

import com.djzy.assistant.common.error.UnifiedErrors;

/**
 * 未认证（§4.5 统一措辞）：登录失败、令牌无效、账号停用**一律同一句话**，不区分原因，防账号探测。
 * 差异只进日志与审计。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super(UnifiedErrors.UNAUTHORIZED);
    }
}
