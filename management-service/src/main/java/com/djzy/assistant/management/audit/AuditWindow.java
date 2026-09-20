package com.djzy.assistant.management.audit;

import java.time.Instant;

/**
 * 审计查询的时间窗（**必填**，§20.5）。
 *
 * <p>三条硬口径：① 左闭右开 {@code [from, to)}（§6.6）；② 缺任一端直接 400——
 * 审计表长期保留（§20.5），不带时间范围的查询会全表扫；③ 单次窗口有上限，
 * 避免管理端一次把整年审计拉进内存。
 *
 * @param from 起始（含）
 * @param to 结束（不含）
 * @param limit 行数上限（服务端再夹一次）
 */
public record AuditWindow(Instant from, Instant to, int limit) {

    /** 单次查询窗口上限：31 天。 */
    public static final java.time.Duration MAX_SPAN = java.time.Duration.ofDays(31);

    /** 单次返回行数上限。 */
    public static final int MAX_LIMIT = 500;

    public static final int DEFAULT_LIMIT = 200;

    /**
     * 解析并夹取时间窗。
     *
     * @throws IllegalArgumentException 缺少 from/to、区间非法或跨度超过 {@link #MAX_SPAN}
     */
    public static AuditWindow parse(String from, String to, Integer limit) {
        Instant start = parseInstant(from, "from");
        Instant end = parseInstant(to, "to");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("时间范围必须满足 from < to（左闭右开，§6.6）");
        }
        if (java.time.Duration.between(start, end).compareTo(MAX_SPAN) > 0) {
            throw new IllegalArgumentException("单次查询时间范围不得超过 " + MAX_SPAN.toDays() + " 天（§20.5）");
        }
        int capped = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return new AuditWindow(start, end, capped);
    }

    private static Instant parseInstant(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少查询时间范围参数 " + what + "（审计查询必须带时间范围，§20.5）");
        }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("时间参数 " + what + " 必须是 ISO-8601 UTC 时刻（如 2026-09-01T00:00:00Z）");
        }
    }
}
