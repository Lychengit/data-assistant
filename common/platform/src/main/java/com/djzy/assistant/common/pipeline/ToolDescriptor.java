package com.djzy.assistant.common.pipeline;

import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCategory;
import java.util.Objects;

/**
 * 工具描述（管线内部视图）。
 *
 * @param maxResultBytes 响应大小上限（§9.4）；超出由 POST 段 spill 成 locator
 * @param writeQuotaPerTurn 单轮写操作上限（默认 3，§19.9）；仅写类工具有意义
 */
public record ToolDescriptor(
        String name,
        SideEffect sideEffect,
        ToolCategory category,
        long maxResultBytes,
        int writeQuotaPerTurn) {

    public static final long DEFAULT_MAX_RESULT_BYTES = 256 * 1024L;
    public static final int DEFAULT_WRITE_QUOTA_PER_TURN = 3;

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(category, "category");
        if (maxResultBytes <= 0) {
            maxResultBytes = DEFAULT_MAX_RESULT_BYTES;
        }
        if (writeQuotaPerTurn <= 0) {
            writeQuotaPerTurn = DEFAULT_WRITE_QUOTA_PER_TURN;
        }
    }

    public static ToolDescriptor read(String name, ToolCategory category) {
        return new ToolDescriptor(name, SideEffect.READ, category, DEFAULT_MAX_RESULT_BYTES, 0);
    }

    public static ToolDescriptor write(String name, ToolCategory category) {
        return new ToolDescriptor(
                name, SideEffect.WRITE, category, DEFAULT_MAX_RESULT_BYTES, DEFAULT_WRITE_QUOTA_PER_TURN);
    }
}
