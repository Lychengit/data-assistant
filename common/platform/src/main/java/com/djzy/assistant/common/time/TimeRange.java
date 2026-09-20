package com.djzy.assistant.common.time;

import java.time.Instant;
import java.util.Objects;

/**
 * 时间区间：一律**左闭右开** {@code [start, end)}（§6.6），避免「月末最后一笔」被算两次或漏掉。
 *
 * <p>相对时间（今天 / 上月 / 本季度 / 最近 7 天）由程序解析，模型不参与日期计算。
 */
public record TimeRange(Instant start, Instant end, String label) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("时间区间必须满足 start < end（左闭右开）");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    public long durationMillis() {
        return end.toEpochMilli() - start.toEpochMilli();
    }
}
