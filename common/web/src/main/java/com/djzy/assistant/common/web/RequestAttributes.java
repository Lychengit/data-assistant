package com.djzy.assistant.common.web;

/** 验签结论在单个请求内的传递键（不落任何全局状态）。 */
public final class RequestAttributes {

    /** 验签得到的调用方 keyId（只用于审计，不含密钥，§20.1.3）。 */
    public static final String CALLER_KEY_ID = "gateway.callerKeyId";

    /** 链路 id。 */
    public static final String TRACE_ID = "gateway.traceId";

    /** 当前登录用户上下文（{@link com.djzy.assistant.common.web.auth.UserContext}）。 */
    public static final String USER_CONTEXT = "gateway.userContext";

    private RequestAttributes() {}
}
