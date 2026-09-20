package com.djzy.assistant.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * 相对时间口径（§6.6）。
 *
 * <p>这些断言的共同目的：把「早上 8 点前差一个月」「跨年差一年」这类边界钉在这里，
 * 而不是让它们出现在用户看到的排名里。
 */
class BusinessTimeTest {

    /** 服务器按 UTC 跑：UTC 8/31 17:00 已是业务时区的 9/1，「上个月」必须是 8 月。 */
    @Test
    void 业务时区折算跨日后再算上个月() {
        Instant utc = Instant.parse("2026-08-31T17:00:00Z");

        LocalDate today = BusinessTime.dateOf(utc);

        assertEquals(LocalDate.of(2026, 9, 1), today);
        assertEquals(YearMonth.of(2026, 8), BusinessTime.previousMonthOf(today));
    }

    @Test
    void 上个月跨年() {
        assertEquals(YearMonth.of(2025, 12), BusinessTime.previousMonthOf(LocalDate.of(2026, 1, 15)));
    }

    @Test
    void 季度起点按自然季度() {
        assertEquals(YearMonth.of(2026, 7), BusinessTime.quarterStartOf(LocalDate.of(2026, 9, 19)));
        assertEquals(YearMonth.of(2026, 1), BusinessTime.quarterStartOf(LocalDate.of(2026, 1, 1)));
        assertEquals(YearMonth.of(2025, 10), BusinessTime.previousQuarterStartOf(LocalDate.of(2026, 2, 10)));
    }

    /** 「最近 7 天」含当天，且左闭右开：[今天-6, 明天)。 */
    @Test
    void 最近七天是左闭右开窗口() {
        BusinessTime.DateRange range = BusinessTime.recentDaysOf(LocalDate.of(2026, 9, 19), 7);

        assertEquals(LocalDate.of(2026, 9, 13), range.start());
        assertEquals(LocalDate.of(2026, 9, 20), range.endExclusive());
    }

    @Test
    void 空区间直接被拒绝() {
        LocalDate day = LocalDate.of(2026, 9, 19);

        assertThrows(IllegalArgumentException.class, () -> new BusinessTime.DateRange(day, day));
    }
}
