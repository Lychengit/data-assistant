package com.djzy.assistant.common.security;

/** 服务间签名头部常量（§20.1.3）。 */
public final class SignatureHeaders {

    public static final String API_KEY = "X-Api-Key";
    public static final String TIMESTAMP = "X-Timestamp";
    public static final String NONCE = "X-Nonce";
    public static final String SIGNATURE = "X-Signature";
    public static final String ALGORITHM_VERSION = "v1";
    public static final String SIGNATURE_PREFIX = ALGORITHM_VERSION + ":";

    private SignatureHeaders() {}
}
