package com.djzy.assistant.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 业务时区与相对时间口径（§6.6）：固定 {@code Asia/Shanghai}，**不跟随服务器时区**。
 *
 * <p>存与算分离：DB 时间列统一 {@code timestamptz} 存 UTC；查询时按业务时区切成区间再下推到 SQL。
 *
 * <p>为什么相对时间要在这里算：服务器按 UTC 跑时，早上 8 点前算出来的「上个月」会整整差一个月；
 * 跨年、闰月、月末同样是模型最容易算错的地方。所以「今天 / 上月 / 本季度 / 最近 7 天」一律由程序
 * 解析成具体日期，模型只负责把用户的话映射到这些口径上（§6.6「相对时间由程序解析」）。
 *
 * <p>区间一律**左闭右开** {@code [start, end)}：这样月末最后一笔不会被算两次，也不会漏掉。
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 「最近 N 天」在口径里含今天，所以窗口是 [今天-(N-1), 明天)。 */
    public static final int RECENT_DAYS = 7;

    private BusinessTime() {}

    public static ZonedDateTime now() {
        return ZonedDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 把 UTC 时间点折算成业务时区日期：跨时区比较时不允许直接用 {@code Instant} 的日期部分。 */
    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static YearMonth monthOf(LocalDate date) {
        return YearMonth.from(date);
    }

    public static YearMonth previousMonthOf(LocalDate date) {
        return YearMonth.from(date).minusMonths(1);
    }

    /** 本季度起始月（1/4/7/10 月）。 */
    public static YearMonth quarterStartOf(LocalDate date) {
        int firstMonthOfQuarter = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return YearMonth.of(date.getYear(), firstMonthOfQuarter);
    }

    public static YearMonth previousQuarterStartOf(LocalDate date) {
        return quarterStartOf(date).minusMonths(3);
    }

    /** 最近 N 天的左闭右开窗口 {@code [start, end)}。 */
    public static DateRange recentDaysOf(LocalDate date, int days) {
        int span = Math.max(1, days);
        return new DateRange(date.minusDays(span - 1L), date.plusDays(1));
    }

    public static Instant toInstant(ZonedDateTime time) {
        return time.toInstant();
    }

    public static Clock clock() {
        return Clock.system(ZONE);
    }

    /** 左闭右开日期区间：{@code start} 含、{@code endExclusive} 不含。 */
    public record DateRange(LocalDate start, LocalDate endExclusive) {

        public DateRange {
            if (start == null || endExclusive == null || !start.isBefore(endExclusive)) {
                throw new IllegalArgumentException("区间必须非空且左闭右开：[" + start + ", " + endExclusive + ")");
            }
        }
    }
}
