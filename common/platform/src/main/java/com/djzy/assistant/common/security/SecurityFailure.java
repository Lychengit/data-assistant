package com.djzy.assistant.common.security;

/**
 * 验签失败原因（§20.1.4）：**对外一律统一 401 措辞**，原因只进审计与告警。
 */
public enum SecurityFailure {
    MISSING_HEADERS,
    UNKNOWN_KEY,
    TIMESTAMP_OUT_OF_WINDOW,
    NONCE_REPLAYED,
    INVALID_SIGNATURE_FORMAT,
    SIGNATURE_MISMATCH
}
