package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;

/**
 * 从「这周哪几天上班」反推制度（v1.2.0）。
 *
 * <p>这是整版里唯一「猜用户意思」的地方，猜错的代价是整台手机的班表错位——
 * 用户第二天照常上班，闹钟没响，而他要过好几天才会怀疑到「引导页那七个格子」。
 * 所以每一种形状、每一条边界都得有断言。
 */
@RunWith(RobolectricTestRunner.class)
public class ScheduleInferTest {

    /** 2026-11-02 是周一。 */
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    /** 「上班」用 1 表示，写起来像一张班表。 */
    private static boolean[] days(String pattern) {
        boolean[] out = new boolean[pattern.length()];
        for (int i = 0; i < pattern.length(); i++) out[i] = pattern.charAt(i) == '1';
        return out;
    }

    private static final String WORKWEEK = "1111100";   // 周一到周五
    private static final String REST_SUN = "1111110";   // 单休：只休周日
    private static final String REST_SAT = "1111101";   // 单休：只休周六

    // ================= 七天 =================

    @Test
    public void doubleRest_isRecognised() {
        assertEquals(WeekSchedule.Mode.DOUBLE_REST,
                ScheduleInfer.infer(days(WORKWEEK), MON).mode);
    }

    @Test
    public void singleRest_mapsToCorrectWeekday() {
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN,
                ScheduleInfer.infer(days(REST_SUN), MON).mode);
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SAT,
                ScheduleInfer.infer(days(REST_SAT), MON).mode);
    }

    /** 周三也休息：不像任何一种具名制度，落 7 天周期。 */
    @Test
    public void midweekRest_fallsBackToCycle() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days("1101100"), MON);

        assertEquals(WeekSchedule.Mode.CYCLE, r.mode);
        assertEquals(7, r.cycleLen);
        assertEquals(MON, r.cycleStart);
        assertEquals("周一二四五上班", 0b0011011L, r.cycleMask);
    }

    /** 全周上班：也是 CYCLE，不能因为「前五天都是 1」就误判成双休。 */
    @Test
    public void everyDayWork_isCycleNotDoubleRest() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days("1111111"), MON);

        assertEquals(WeekSchedule.Mode.CYCLE, r.mode);
        assertEquals(0b1111111L, r.cycleMask);
    }

    @Test
    public void everyDayRest_isCycleWithEmptyMask() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days("0000000"), MON);

        assertEquals(WeekSchedule.Mode.CYCLE, r.mode);
        assertEquals(0L, r.cycleMask);
    }

    /** 空输入不能崩，也不能返回空班表——给最常见的「周一到周五」。 */
    @Test
    public void emptyInput_fallsBackToWorkweek() {
        ScheduleInfer.Result r = ScheduleInfer.infer(null, MON);

        assertEquals(WeekSchedule.Mode.CYCLE, r.mode);
        assertEquals(0b0011111L, r.cycleMask);
    }

    // ================= 十四天 =================

    /** 两周一样：退化成七天，不该存成 14 天周期。 */
    @Test
    public void twoIdenticalWeeks_collapseToSeven() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days(WORKWEEK + WORKWEEK), MON);

        assertEquals(WeekSchedule.Mode.DOUBLE_REST, r.mode);
    }

    /** 本周双休、下周只休周日 = 标准大小周，本周是大周。 */
    @Test
    public void bigWeekFirst_isRecognised() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days(WORKWEEK + REST_SUN), MON);

        assertEquals(WeekSchedule.Mode.BIG_SMALL, r.mode);
        assertEquals("大周的周六 = 本周六", MON.plusDays(5), r.anchor);
        assertFalse("小周休周日，不是周六", r.smallRestSat);
    }

    /** 本周只休周日、下周双休 = 本周是小周，anchor 要落到下周六。 */
    @Test
    public void smallWeekFirst_anchorsNextSaturday() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days(REST_SUN + WORKWEEK), MON);

        assertEquals(WeekSchedule.Mode.BIG_SMALL, r.mode);
        assertEquals("大周的周六 = 下周六", MON.plusDays(12), r.anchor);
    }

    /** 小周休周六的变体（少数公司如此），要认出来并带上 smallRestSat。 */
    @Test
    public void smallWeekRestingSaturday_setsFlag() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days(WORKWEEK + REST_SAT), MON);

        assertEquals(WeekSchedule.Mode.BIG_SMALL, r.mode);
        assertTrue("小周休的是周六", r.smallRestSat);
    }

    /** 上三休四、上四休三这类不规则轮换：14 天周期，mask 要把两周都装进去。 */
    @Test
    public void irregularTwoWeeks_stayFourteenDayCycle() {
        ScheduleInfer.Result r = ScheduleInfer.infer(days("11100001111000"), MON);

        assertEquals(WeekSchedule.Mode.CYCLE, r.mode);
        assertEquals(14, r.cycleLen);
        assertEquals(MON, r.cycleStart);
        // 第 1~3 天、第 8~11 天上班
        assertEquals(0b11110000111L, r.cycleMask);
    }

    // ================= 反向：规则 → 格子 =================

    /** 双休摊成格子就是「一二三四五班，六日休」。 */
    @Test
    public void expand_doubleRest() {
        WeekSchedule.Rule rule = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false);
        assertArrayEquals(days(WORKWEEK), ScheduleInfer.expand(rule, MON, 7));
    }

    /**
     * 关键：格子画的是「你的班表」，不是「日历上这天放不放假」。
     *
     * <p>国庆正好落在这一周时，{@code isWorkday} 会说「不上班」；格子若照它画，
     * 用户点一下就放假进班表，明年国庆他以为在上班、闹钟不响。
     */
    @Test
    public void expand_ignoresHolidays() {
        LocalDate nationalWeekMon = LocalDate.of(2026, 9, 28);   // 10/1 周四在这一周
        assertTrue("前提：10/1 是法定假日",
                HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));

        WeekSchedule.Rule rule = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false);
        boolean[] week = ScheduleInfer.expand(rule, nationalWeekMon, 7);

        assertTrue("国庆那格仍应显示「班」——那是他的排班，不是日历", week[3]);
        assertArrayEquals(days(WORKWEEK), week);
    }

    /** 大小周摊成 14 天，两周要不一样。 */
    @Test
    public void expand_bigSmallDiffersByWeek() {
        WeekSchedule.Rule rule = new WeekSchedule.Rule(
                WeekSchedule.Mode.BIG_SMALL, MON.plusDays(5), false);

        boolean[] two = ScheduleInfer.expand(rule, MON, 14);

        assertArrayEquals("大周双休", days(WORKWEEK), java.util.Arrays.copyOfRange(two, 0, 7));
        assertArrayEquals("小周只休周日", days(REST_SUN), java.util.Arrays.copyOfRange(two, 7, 14));
    }

    /** 轮班规则摊出来要跟它自己算的一致。 */
    @Test
    public void expand_cycle() {
        WeekSchedule.Rule rule = WeekSchedule.Rule.cycle(4, 0b0011L, MON, null, false);

        boolean[] wk = ScheduleInfer.expand(rule, MON, 7);

        assertArrayEquals(days("1100110"), wk);
    }
}
