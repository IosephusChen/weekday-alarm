package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 大小周排班算法测试：14 天循环、周六归属、节假日/补休优先级。
 */
public class WeekScheduleTest {

    // 选 2026-10-17（周六）为「大周周六」锚点
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 17);

    private static WeekSchedule.DayType type(LocalDate d, boolean smallRestSat) {
        return WeekSchedule.dayType(d, ANCHOR, smallRestSat);
    }

    @Test
    public void bigWeekSaturdayRest_smallWeekSaturdayWork() {
        assertTrue(ANCHOR.getDayOfWeek() == DayOfWeek.SATURDAY);
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR, false));                 // 大周周六休
        assertEquals(WeekSchedule.DayType.WORK, type(ANCHOR.plusDays(7), false));     // 小周周六班
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR.plusDays(14), false));    // 14 天回到大周
        assertEquals(WeekSchedule.DayType.WORK, type(ANCHOR.plusDays(21), false));
    }

    @Test
    public void smallRestSat_swapsSaturdaySunday() {
        // 小周休周六（周日上班）模式：小周周六休、周日班；大周不受影响
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR.plusDays(7), true));      // 小周周六休
        assertEquals(WeekSchedule.DayType.WORK, type(ANCHOR.plusDays(8), true));      // 小周周日班
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR, true));                  // 大周周六仍休
    }

    /**
     * v0.91.9 回归：选「小周休周六」时，<b>大周的周日也必须是休息</b>。
     *
     * <p>上一个测试的注释写着「大周不受影响」，却只断言了大周周六——周日那行漏了。
     * 而 dayType 里周日分支写的是 {@code smallRestSat ? WORK : REST}，压根没看大周，
     * 于是这个模式下大周周日被判成上班：14 天里两个周日全在上班，大周的双休退化成单休，
     * 闹钟在周日照样响。这是「测试覆盖了注释想说的，却没覆盖代码实际做的」的典型。
     */
    @Test
    public void smallRestSat_bigWeekSundayStillRests() {
        assertEquals(DayOfWeek.SUNDAY, ANCHOR.plusDays(1).getDayOfWeek());
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR.plusDays(1), true));      // 大周周日休
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR.plusDays(15), true));     // 下一个大周周日也休
        // 对照组：小周周日仍然是上班日，没有被这条改动带偏
        assertEquals(WeekSchedule.DayType.WORK, type(ANCHOR.plusDays(8), true));
        assertEquals(WeekSchedule.DayType.WORK, type(ANCHOR.plusDays(22), true));
    }

    /** 默认模式（小周只休周日）下，大周周日一直是休息——这条本来就对，钉住防止改坏。 */
    @Test
    public void defaultMode_bigWeekSundayRests() {
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR.plusDays(1), false));
        assertEquals(WeekSchedule.DayType.REST, type(ANCHOR.plusDays(8), false));
    }

    @Test
    public void weekdaysAlwaysWork() {
        for (int i = 0; i < 28; i++) {
            LocalDate d = ANCHOR.plusDays(i);
            if (d.getDayOfWeek().getValue() <= 5) {
                assertEquals(WeekSchedule.DayType.WORK, type(d, false));
                assertEquals(WeekSchedule.DayType.WORK, type(d, true));
            }
        }
    }

    @Test
    public void holidayOverridesBigWeekSchedule() {
        // 2026-10-01 国庆放假：无论大小周判定如何都是假日
        assertEquals(WeekSchedule.DayType.HOLIDAY,
                WeekSchedule.dayType(LocalDate.of(2026, 10, 1), ANCHOR, false));
        assertEquals(WeekSchedule.DayType.HOLIDAY,
                WeekSchedule.dayType(LocalDate.of(2026, 10, 1), ANCHOR, true));
    }

    @Test
    public void makeupOverridesRest() {
        // 2026-10-10（周六）调休补班：即使大周判定该休也上班
        assertEquals(WeekSchedule.DayType.MAKEUP_WORK,
                WeekSchedule.dayType(LocalDate.of(2026, 10, 10), ANCHOR, false));
        // 2026-09-20（周日）补班
        assertEquals(WeekSchedule.DayType.MAKEUP_WORK,
                WeekSchedule.dayType(LocalDate.of(2026, 9, 20), ANCHOR, true));
    }

    @Test
    public void isWorkday_matchesDayType() {
        assertTrue(WeekSchedule.isWorkday(LocalDate.of(2026, 10, 1), ANCHOR, false)
                == false);   // 国庆放假不是上班日
        assertTrue(WeekSchedule.isWorkday(LocalDate.of(2026, 10, 10), ANCHOR, false));  // 补班是上班日
        assertTrue(WeekSchedule.isWorkday(LocalDate.of(2026, 10, 19), ANCHOR, false));  // 周一上班
    }

    @Test
    public void nextWorkday_skipsHolidaysAndWeekends() {
        // 2026-10-01 放假 → 下一个上班日应为 10-09（周五，10-10 补班前一天普通工作日？）
        // 实际：10-01~10-07 放假，10-08 周四上班
        LocalDate next = WeekSchedule.nextWorkdayOnOrAfter(LocalDate.of(2026, 10, 1), ANCHOR, false);
        assertEquals(LocalDate.of(2026, 10, 8), next);
    }

    @Test
    public void anchorShiftsByWeek() {
        // 大小周判定只与「距锚点周数」的奇偶有关，与具体日期无关
        assertEquals(type(ANCHOR, false), type(ANCHOR.plusDays(28), false));
        assertEquals(type(ANCHOR.plusDays(7), false), type(ANCHOR.plusDays(35), false));
    }
}
