package com.djzy.assistant.spi;

import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolInvoker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 平台 → 运行时的中立请求（§18.11.2）：运行时拿不到 DB、网关密钥，只拿到可见工具清单与一个回调。
 */
public final class AgentRunRequest {

    private final String userId;
    private final String sessionId;
    private final String requestId;
    private final String systemPromptPrefix;
    private final String systemPromptSuffix;
    private final ToolCatalog tools;
    private final ToolInvoker toolInvoker;
    private final RuntimeStatePort statePort;
    private final long deadlineEpochMs;
    private final int maxIters;
    private final Map<String, Object> attributes;

    private AgentRunRequest(Builder b) {
        this.userId = Objects.requireNonNull(b.userId, "userId");
        this.sessionId = Objects.requireNonNull(b.sessionId, "sessionId");
        this.requestId = Objects.requireNonNull(b.requestId, "requestId");
        this.systemPromptPrefix = b.systemPromptPrefix == null ? "" : b.systemPromptPrefix;
        this.systemPromptSuffix = b.systemPromptSuffix == null ? "" : b.systemPromptSuffix;
        this.tools = b.tools == null ? ToolCatalog.empty() : b.tools;
        this.toolInvoker = Objects.requireNonNull(b.toolInvoker, "toolInvoker");
        this.statePort = Objects.requireNonNull(b.statePort, "statePort");
        this.deadlineEpochMs = b.deadlineEpochMs;
        this.maxIters = b.maxIters;
        this.attributes = Map.copyOf(b.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String requestId() {
        return requestId;
    }

    /** 固定前缀（前缀缓存友好）。 */
    public String systemPromptPrefix() {
        return systemPromptPrefix;
    }

    /** 每轮尾部追加的 delta（ADR-27 onSystemPrompt）。 */
    public String systemPromptSuffix() {
        return systemPromptSuffix;
    }

    public ToolCatalog tools() {
        return tools;
    }

    /** 唯一工具执行出口（平台实现，§9.5 / ADR-32 ③）。 */
    public ToolInvoker toolInvoker() {
        return toolInvoker;
    }

    public RuntimeStatePort statePort() {
        return statePort;
    }

    public long deadlineEpochMs() {
        return deadlineEpochMs;
    }

    public int maxIters() {
        return maxIters;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public static final class Builder {
        private String userId;
        private String sessionId;
        private String requestId;
        private String systemPromptPrefix;
        private String systemPromptSuffix;
        private ToolCatalog tools;
        private ToolInvoker toolInvoker;
        private RuntimeStatePort statePort;
        private long deadlineEpochMs;
        private int maxIters = 10;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        public Builder userId(String v) {
            this.userId = v;
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder requestId(String v) {
            this.requestId = v;
            return this;
        }

        public Builder systemPromptPrefix(String v) {
            this.systemPromptPrefix = v;
            return this;
        }

        public Builder systemPromptSuffix(String v) {
            this.systemPromptSuffix = v;
            return this;
        }

        public Builder tools(ToolCatalog v) {
            this.tools = v;
            return this;
        }

        public Builder toolInvoker(ToolInvoker v) {
            this.toolInvoker = v;
            return this;
        }

        public Builder statePort(RuntimeStatePort v) {
            this.statePort = v;
            return this;
        }

        public Builder deadlineEpochMs(long v) {
            this.deadlineEpochMs = v;
            return this;
        }

        public Builder maxIters(int v) {
            this.maxIters = v;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public AgentRunRequest build() {
            return new AgentRunRequest(this);
        }
    }
}
