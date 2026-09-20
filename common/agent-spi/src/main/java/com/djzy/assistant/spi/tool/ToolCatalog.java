package com.djzy.assistant.spi.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可见工具清单（节点 A 的产物）：只装 {@code visibleTools}，运行时无法调用清单外工具。
 */
public final class ToolCatalog {

    private static final ToolCatalog EMPTY = new ToolCatalog(List.of());

    private final List<ToolSpec> tools;
    private final Map<String, ToolSpec> byName;

    private ToolCatalog(List<ToolSpec> tools) {
        this.tools = List.copyOf(tools);
        Map<String, ToolSpec> m = new LinkedHashMap<>();
        for (ToolSpec t : this.tools) {
            m.put(t.name(), t);
        }
        this.byName = Map.copyOf(m);
    }

    public static ToolCatalog empty() {
        return EMPTY;
    }

    public static ToolCatalog of(List<ToolSpec> tools) {
        return tools == null || tools.isEmpty() ? EMPTY : new ToolCatalog(tools);
    }

    public List<ToolSpec> all() {
        return tools;
    }

    public Optional<ToolSpec> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    public int size() {
        return tools.size();
    }
}
