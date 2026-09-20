package com.djzy.assistant.core.tool;

import com.djzy.assistant.common.pipeline.ToolDescriptor;
import com.djzy.assistant.common.pipeline.ToolExecution;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表（平台侧）：运行时只能调用**清单内**工具；清单外一律拒绝（节点 C / L1 白名单）。
 */
public final class ToolRegistry {

    private final Map<String, RegisteredTool> tools;

    private ToolRegistry(Map<String, RegisteredTool> tools) {
        this.tools = Map.copyOf(tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Map<String, RegisteredTool> all() {
        return tools;
    }

    public record RegisteredTool(ToolDescriptor descriptor, ToolExecution execution) {}

    public static final class Builder {
        private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

        public Builder register(ToolDescriptor descriptor, ToolExecution execution) {
            tools.put(descriptor.name(), new RegisteredTool(descriptor, execution));
            return this;
        }

        public ToolRegistry build() {
            return new ToolRegistry(tools);
        }
    }
}
