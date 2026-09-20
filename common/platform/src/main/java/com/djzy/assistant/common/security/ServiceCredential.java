package com.djzy.assistant.common.security;

import java.util.Objects;

/**
 * 服务间凭证（§20.1.2）：{@code keyId} 明文标识（可进日志），{@code sharedSecret} 只在调用方与被调方之间共享。
 *
 * <p>密钥按调用方逐一分配，不使用全局共用密钥；不进 git、不进日志、不进镜像。
 */
public record ServiceCredential(String keyId, String sharedSecret) {

    public ServiceCredential {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(sharedSecret, "sharedSecret");
    }
}
