package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 排班统计（v0.92.0）：日历页与 4×4 小部件底部那句「本月 20 天班 · 11 天休」。
 *
 * <p>这条统计原来只在小部件里，日历页加了一处之后，最怕的就是两边各算各的——
 * 这个项目已经在「假/休」标签上吃过一次亏（两边各写一份，后来漂了）。所以这里的
 * 核心用例不是文案，而是 {@link #calendarAndWidget_agreeOnSameMonth()}：
 * 两条不同的遍历路径必须数出同一个数。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MonthStatsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 「算不算上班」的四个象限，就是格子上那四种底色。 */
    @Test
    public void countsAsWork_fourQuadrants() {
        assertTrue("上班日没请假 → 算",
                MonthStats.countsAsWork(WeekSchedule.DayType.WORK, false, false));
        assertFalse("上班日请了假 → 不算",
                MonthStats.countsAsWork(WeekSchedule.DayType.WORK, true, false));
        assertTrue("休息日标了加班 → 算",
                MonthStats.countsAsWork(WeekSchedule.DayType.REST, false, true));
        assertFalse("休息日什么都没标 → 不算",
                MonthStats.countsAsWork(WeekSchedule.DayType.REST, false, false));
        // 补班日本来就是上班日，语义跟 WORK 一样
        assertTrue(MonthStats.countsAsWork(WeekSchedule.DayType.MAKEUP_WORK, false, false));
    }

    /**
     * 「本月」只在真的显示当月时出现。
     *
     * <p>日历页能翻到 9 月，翻过去还写「本月 22 天班」就是指着错的东西说话了。
     */
    @Test
    public void text_saysThisMonthOnlyForCurrentMonth() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        assertEquals("本月 20 天班 · 11 天休",
                MonthStats.text(YearMonth.of(2026, 10), today, 20));
        assertEquals("9 月 22 天班 · 8 天休",
                MonthStats.text(YearMonth.of(2026, 9), today, 22));
    }

    /** 跨年也不能把 12 月说成「本月」。 */
    @Test
    public void text_crossYearStillSaysMonthNumber() {
        LocalDate today = LocalDate.of(2027, 1, 5);
        assertEquals("12 月 23 天班 · 8 天休",
                MonthStats.text(YearMonth.of(2026, 12), today, 23));
        assertEquals("本月 21 天班 · 10 天休",
                MonthStats.text(YearMonth.of(2027, 1), today, 21));
    }

    /**
     * 日历页和小部件必须数出同一个数。
     *
     * <p>小部件走的是「42 格里属于本月的那些」，日历页走的是「整月 1..len」，
     * 两条路径一旦有一边把补班日或请假算反，这条就会红。
     */
    @Test
    public void calendarAndWidget_agreeOnSameMonth() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        WeekSchedule.Rule rule = Prefs.getRule(ctx());

        String fromCalendarPath = MonthStats.text(YearMonth.of(2026, 10), today, rule,
                Prefs.getVacationDates(ctx()), Prefs.getOvertimeDates(ctx()));
        MonthWidgetRender.Model m = MonthWidgetRender.compute(ctx(), today, null);

        assertEquals("两处统计必须同源", m.stats, fromCalendarPath);
    }

    /**
     * 标了一天假，两边的数要一起变——一边变一边不变就是漂了。
     *
     * <p>不写死「20 → 19」：10/12 是不是上班日取决于默认周休规则，写死会把
     * 一条口径测试变成对默认设置的断言。
     */
    @Test
    public void markingVacation_movesBothSidesTogether() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        LocalDate target = LocalDate.of(2026, 10, 12);
        assertTrue("前提：这天原本是上班日", DayOffAction.isWorkDay(
                WeekSchedule.dayType(target, Prefs.getRule(ctx()))));

        String before = MonthWidgetRender.compute(ctx(), today, null).stats;
        Prefs.addVacation(ctx(), target);
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        String calendarAfter = MonthStats.text(YearMonth.of(2026, 10), today, rule,
                Prefs.getVacationDates(ctx()), Prefs.getOvertimeDates(ctx()));
        String widgetAfter = MonthWidgetRender.compute(ctx(), today, null).stats;

        assertEquals("两处仍然同源", widgetAfter, calendarAfter);
        assertEquals("请了一天假，班的天数少一天", workDaysOf(before) - 1, workDaysOf(calendarAfter));
    }

    /** 从「本月 20 天班 · 11 天休」/「9 月 22 天班 · 8 天休」里取班的天数。 */
    private static int workDaysOf(String stats) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+) 天班").matcher(stats);
        assertTrue("统计里应含「N 天班」，实际：" + stats, m.find());
        return Integer.parseInt(m.group(1));
    }
}
