package com.djzy.assistant.common.web;

/** 验签结论在单个请求内的传递键（不落任何全局状态）。 */
public final class RequestAttributes {

    /** 验签得到的调用方 keyId（只用于审计，不含密钥，§20.1.3）。 */
    public static final String CALLER_KEY_ID = "gateway.callerKeyId";

    /** 链路 id。 */
    public static final String TRACE_ID = "gateway.traceId";

    private RequestAttributes() {}
}
