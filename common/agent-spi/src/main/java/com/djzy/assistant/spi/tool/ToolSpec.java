package com.djzy.assistant.spi.tool;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具声明：运行时只拿到「可见工具清单 + schema」，拿不到数据面凭据（§18.11.3）。
 *
 * @param name 工具名（{@code iface_*} / {@code skill_*} / {@code py_*} / 平台内置）
 * @param description 给模型看的说明
 * @param inputSchema JSON Schema（结构化白名单，§11.1；用户输入永不直接拼接 SQL）
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, Object> inputSchema,
        SideEffect sideEffect,
        ToolCategory category,
        Set<String> tags) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(category, "category");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public static ToolSpec read(String name, String description, Map<String, Object> schema, ToolCategory category) {
        return new ToolSpec(name, description, schema, SideEffect.READ, category, Set.of());
    }
}
