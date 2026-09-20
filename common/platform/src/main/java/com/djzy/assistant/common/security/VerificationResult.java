package com.djzy.assistant.common.security;

import java.util.Optional;

/**
 * 验签结论（§20.1.4）。
 *
 * <p>注意：验签只证明「请求来自可信服务且未被篡改」，**不替代网关单点判定**；
 * 接口服务验签通过后直接使用网关下发的身份与范围，不查权限库、不回查网关。
 */
public record VerificationResult(boolean verified, SecurityFailure failure, String keyId) {

    public static VerificationResult ok(String keyId) {
        return new VerificationResult(true, null, keyId);
    }

    public static VerificationResult fail(SecurityFailure failure, String keyId) {
        return new VerificationResult(false, failure, keyId);
    }

    public Optional<SecurityFailure> failureIfPresent() {
        return Optional.ofNullable(failure);
    }
}
