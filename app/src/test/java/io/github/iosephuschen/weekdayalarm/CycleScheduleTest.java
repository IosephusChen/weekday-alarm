package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

/**
 * 自定义 / 轮班（v1.1.0，{@link WeekSchedule.Mode#CYCLE}）的排班测试。
 *
 * <p>这一套算法最容易错在三处，测试按这三点组织：
 * <ol>
 *   <li><b>循环索引</b>：目标日期早于起始日时必须落在 [0, len) 里，不能出负数</li>
 *   <li><b>节假日</b>：轮班默认**不跟**节假日（春节照上班），开关打开才跟</li>
 *   <li><b>脏数据</b>：周期 0 天、超过 62 天、时间和长度对不上，都不能让排班炸掉</li>
 * </ol>
 */
public class CycleScheduleTest {

    /** 2026-11-02 是周一，拿它当大多数用例的起始日。 */
    private static final LocalDate START = LocalDate.of(2026, 11, 2);

    /** 上二休二：4 天循环，第 1、2 天上班。 */
    private static WeekSchedule.Rule shang2xiu2(LocalDate start) {
        return WeekSchedule.Rule.cycle(4, 0b0011L, start, null, false);
    }

    private static WeekSchedule.DayType type(LocalDate d, WeekSchedule.Rule r) {
        return WeekSchedule.dayType(d, r);
    }

    // ================= 基本循环 =================

    @Test
    public void fourDayCycle_repeatsExactly() {
        WeekSchedule.Rule r = shang2xiu2(START);
        assertEquals(WeekSchedule.DayType.WORK, type(START, r));
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(1), r));
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(2), r));
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(3), r));
        // 第 5 天回到第 1 天
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(4), r));
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(5), r));
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(6), r));
    }

    @Test
    public void fourteenDayCycle_usedAsBigSmallWeek() {
        // 用 14 天轮班拼大小周：第 1~5 天班、第 6 天班（小周周六）、第 7 天休、
        // 第 8~12 天班、第 13、14 天休。
        long mask = 0b0011111L          // 第 1~5 天
                | (1L << 5)             // 第 6 天（小周周六上班）
                | (0b11111L << 7)       // 第 8~12 天
                ;
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(14, mask, START, null, false);

        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(5), r));    // 小周周六
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(6), r));    // 小周周日
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(12), r));   // 大周周六
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(13), r));   // 大周周日
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(14), r));   // 14 天后回到第 1 天
    }

    @Test
    public void customSevenDay_restOnWedAndSat() {
        // 自定义：周三、周六休（bit2 / bit5 为 0）
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(7, 0b1011011L, START, null, true);
        assertEquals(WeekSchedule.DayType.REST, type(LocalDate.of(2026, 11, 4), r));   // 周三
        assertEquals(WeekSchedule.DayType.REST, type(LocalDate.of(2026, 11, 7), r));   // 周六
        assertEquals(WeekSchedule.DayType.WORK, type(LocalDate.of(2026, 11, 9), r));   // 下周一
        assertEquals(WeekSchedule.DayType.WORK, type(LocalDate.of(2026, 11, 8), r));   // 周日上班
    }

    // ================= 往前看：索引不能出负数 =================

    @Test
    public void dateBeforeStart_stillInRange() {
        WeekSchedule.Rule r = shang2xiu2(START);
        // 起始日往前 3 天 → 索引 floorMod(-3, 4) = 1 → 第 2 天，上班
        assertEquals(1, WeekSchedule.cycleIndex(START.minusDays(3), r));
        assertEquals(WeekSchedule.DayType.WORK, type(START.minusDays(3), r));
        // 往前 2 天 → 索引 2 → 休息
        assertEquals(2, WeekSchedule.cycleIndex(START.minusDays(2), r));
        assertEquals(WeekSchedule.DayType.REST, type(START.minusDays(2), r));
        // 往前 1 年也不许抛异常、不许越界
        int idx = WeekSchedule.cycleIndex(START.minusYears(1), r);
        assertTrue(idx >= 0 && idx < 4);
    }

    @Test
    public void cycleIndex_neverNegative_forAnyOffset() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(7, 0b1111111L, START, null, false);
        for (int offset = -400; offset <= 400; offset++) {
            int idx = WeekSchedule.cycleIndex(START.plusDays(offset), r);
            assertTrue("offset=" + offset + " idx=" + idx, idx >= 0 && idx < 7);
        }
    }

    // ================= 节假日 =================

    @Test
    public void rotation_ignoresHoliday_byDefault() {
        // 春节 2026-02-17 在假期里，但轮班的人照上班
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(1, 0b1L, START, null, false);
        assertEquals(LocalDate.of(2026, 2, 17).getMonthValue(), 2);   // 防手滑写错年份
        assertEquals(WeekSchedule.DayType.WORK, type(LocalDate.of(2026, 2, 17), r));
        assertEquals(WeekSchedule.DayType.WORK, type(LocalDate.of(2026, 10, 1), r));
    }

    @Test
    public void rotation_followsHoliday_whenSwitchOn() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(1, 0b1L, START, null, true);
        assertEquals(WeekSchedule.DayType.HOLIDAY, type(LocalDate.of(2026, 2, 17), r));
        assertEquals(WeekSchedule.DayType.HOLIDAY, type(LocalDate.of(2026, 10, 1), r));
        // 调休补班日：即使这天在循环里本来是休息，也要上班
        WeekSchedule.Rule restThatDay = WeekSchedule.Rule.cycle(1, 0b0L, START, null, true);
        assertEquals(WeekSchedule.DayType.MAKEUP_WORK, type(LocalDate.of(2026, 2, 14), restThatDay));
    }

    @Test
    public void rotation_ignoresMakeupDay_whenSwitchOff() {
        // 开关关闭时，补班日也按循环走——循环说休就休
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(1, 0b0L, START, null, false);
        assertEquals(WeekSchedule.DayType.REST, type(LocalDate.of(2026, 2, 14), r));
    }

    // ================= 班表时间 =================

    @Test
    public void cycleTime_returnedForWorkdayOnly() {
        int[] times = {6 * 60, 14 * 60, 22 * 60, -1};   // 早 / 中 / 夜 / 休
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(4, 0b0111L, START, times, false);

        assertEquals(360, WeekSchedule.cycleTimeOf(START, r));
        assertEquals(840, WeekSchedule.cycleTimeOf(START.plusDays(1), r));
        assertEquals(1320, WeekSchedule.cycleTimeOf(START.plusDays(2), r));
        assertEquals(-1, WeekSchedule.cycleTimeOf(START.plusDays(3), r));      // 休息
        assertEquals(360, WeekSchedule.cycleTimeOf(START.plusDays(4), r));     // 下一轮
    }

    @Test
    public void cycleTime_nullWhenNotSet() {
        WeekSchedule.Rule r = shang2xiu2(START);
        assertEquals(-1, WeekSchedule.cycleTimeOf(START, r));
    }

    // ================= 脏数据兜底 =================

    @Test
    public void zeroLength_doesNotThrow() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(0, 0b1L, START, null, false);
        assertEquals(1, r.cycleLen);
        assertEquals(WeekSchedule.DayType.WORK, type(START, r));
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(1), r));
    }

    @Test
    public void oversizeLength_clampedTo62() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(200, -1L, START, null, false);
        assertEquals(WeekSchedule.Rule.MAX_CYCLE_LEN, r.cycleLen);
        assertEquals(62, r.cycleWorkDays());
    }

    @Test
    public void nullStart_fallsBackToToday() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(4, 0b0011L, null, null, false);
        assertEquals(LocalDate.now(), r.cycleStart);
    }

    @Test
    public void mismatchedTimes_droppedInsteadOfCrashing() {
        int[] wrong = {360, 480};   // 周期是 4 天，只给了 2 个
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(4, 0b1111L, START, wrong, false);
        assertNull(r.cycleTimes);
        assertEquals(-1, WeekSchedule.cycleTimeOf(START, r));
    }

    @Test
    public void maskOutOfRange_bitsTrimmed() {
        // 周期 4 天却给了 8 天的位，第 5 天以后的位要被剪掉
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(4, 0xFFL, START, null, false);
        assertEquals(4, r.cycleWorkDays());
    }

    // ================= 62 天长周期 =================

    @Test
    public void sixtyTwoDayCycle_works() {
        // 只上最后一天：验证第 62 天（bit61）也能正确取到
        long mask = 1L << 61;
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(62, mask, START, null, false);
        assertEquals(WeekSchedule.DayType.REST, type(START, r));
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(61), r));
        assertEquals(WeekSchedule.DayType.REST, type(START.plusDays(62), r));
        assertEquals(WeekSchedule.DayType.WORK, type(START.plusDays(123), r));
        assertEquals(1, r.cycleWorkDays());
    }

    // ================= 文案 =================

    @Test
    public void describe_rotationShowsCycleAndWorkDays() {
        // v1.3.0 术语收口：按形状叫「N 天一轮」，不再叫「轮班」
        assertEquals("4 天一轮 · 上 2 休 2", WeekSchedule.describe(shang2xiu2(START), START));
    }

    @Test
    public void describe_customShowsRestDays() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(7, 0b1011011L, START, null, true);
        assertEquals("每周固定 · 休 2 天", WeekSchedule.describe(r, START));
    }

    /**
     * 七天全上班（v1.4.0）：说「休0天」既不通顺，也没说清后果——这张表意味着闹钟天天响。
     * 从双休出发点两下就能到这个状态，结论条不能还装作这是种正常制度。
     */
    @Test
    public void describe_allWorkSaysEveryDay() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(7, 0b1111111L, START, null, false);
        assertEquals("每天都要上班", WeekSchedule.describe(r, START));
    }

    /**
     * 十四天里两周排得一模一样，本质就是「每周固定」（v1.4.0）。
     *
     * <p>按存储结构说成「14 天一轮 · 上 10 休 4」等于把自己的实现细节当成用户的班型；
     * 而且在「我的班表」里勾一下「两周不一样」——一格都没点——结论条会当场变脸。
     */
    @Test
    public void describe_repeatedHalvesCollapseToOneWeek() {
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(14, 0b00111110011111L, START, null, false);
        assertEquals("每周固定 · 休 2 天", WeekSchedule.describe(r, START));
    }

    /** 两周真不一样时才该说 N 天一轮。 */
    @Test
    public void describe_differentHalvesStayRotation() {
        // 第 1 周双休（上5休2），第 2 周单休（上6休1）
        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(14, 0b01111110011111L, START, null, false);
        assertEquals("14 天一轮 · 上 11 休 3", WeekSchedule.describe(r, START));
    }

    // ================= 与原有四种模式互不影响 =================

    @Test
    public void otherModesUntouched() {
        WeekSchedule.Rule dbl = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, START, false);
        assertFalse(dbl.mode.isCycle());
        assertEquals(WeekSchedule.DayType.WORK, type(LocalDate.of(2026, 11, 4), dbl));
        assertEquals(WeekSchedule.DayType.REST, type(LocalDate.of(2026, 11, 7), dbl));
        assertTrue(WeekSchedule.Mode.CYCLE.isCycle());
        assertEquals(WeekSchedule.Mode.CYCLE,
                WeekSchedule.Mode.fromOrdinal(WeekSchedule.Mode.CYCLE.ordinal()));
    }
}
