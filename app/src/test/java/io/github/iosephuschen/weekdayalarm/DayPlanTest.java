package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 按天覆盖（v1.11.0）：请假与加班合并成「一天一个值」之后，排班算法怎么读它。
 *
 * <p>覆盖层的三条规矩，每条都对着一个以前说不清的场景：
 * <ul>
 *   <li>请假 = 这天不上班，比「轮休」更明确——「仅休息日」的闹钟也不该响；</li>
 *   <li>加班 = 这天换成某个班次，比轮转规则更晚、更具体；</li>
 *   <li>覆盖比法定节假日还高：国庆被叫去值班，标了就是上班。</li>
 * </ul>
 */
public class DayPlanTest {

    private static final LocalDate FRI = LocalDate.of(2026, 11, 6);   // 周五
    private static final LocalDate SAT = LocalDate.of(2026, 11, 7);   // 周六
    private static final LocalDate NATIONAL_DAY = LocalDate.of(2026, 10, 1);

    private static Shift shift(int id, String name, int start) {
        return new Shift(id, name, start, Shift.END_UNKNOWN, 0);
    }

    /** 7 天一轮的起点：2026-11-02 周一。 */
    private static final LocalDate CYCLE_START = LocalDate.of(2026, 11, 2);
    /** 周一到周五上班、周六日休。工作日没填逐日时刻，全部 -1。 */
    private static final long MON_FRI = 0b0011111L;
    private static final int[] NO_TIMES = {-1, -1, -1, -1, -1, -1, -1};

    /**
     * 轮班 + 一张覆盖表 + 若干班次。
     *
     * <p>v1.11.2 起，班次只属于真的排了轮班表的班表（v1.11.3 起判据从「模式是
     * N 天一轮」收窄到「{@code shiftCycle} 非空」）：双休、单休、大小周、以及
     * 只勾星期的「自定义」都没有「几点上班」这一维，覆盖表里的班次 id 在那儿
     * 一律不生效。所以这里必须把 {@code shiftCycle} 也铺出来——只给 {@code dayShifts}
     * 兜底的话，{@code hasShifts()} 为 false，这些用例测的就成了另一件事。
     *
     * <p>表的内容与 {@link #MON_FRI} 对齐：周一到周五上第一个班次，周六日休。
     */
    private static WeekSchedule.Rule rule(Map<LocalDate, Integer> days, Shift... shifts) {
        Shift[] defs = shifts.length == 0 ? null : shifts;
        int[] cycle = new int[7];
        int first = defs == null ? -1 : defs[0].id;
        for (int i = 0; i < 7; i++) cycle[i] = i < 5 ? first : -1;
        return new WeekSchedule.Rule(WeekSchedule.Mode.CYCLE, null, false,
                7, MON_FRI, CYCLE_START, NO_TIMES, true, cycle, defs,
                days, defs);
    }

    private static Map<LocalDate, Integer> days(LocalDate d, int value) {
        Map<LocalDate, Integer> m = new HashMap<>();
        m.put(d, value);
        return m;
    }

    // ================= 请假 =================

    @Test
    public void leave_marksDayAsLeave_notWorkday() {
        WeekSchedule.Rule r = rule(days(FRI, WeekSchedule.DAY_LEAVE));

        assertEquals(WeekSchedule.DayType.LEAVE, WeekSchedule.dayType(FRI, r));
        assertFalse("请假日不算上班", WeekSchedule.isWorkday(FRI, r));
        assertNull("请假日没有班次", WeekSchedule.shiftOn(FRI, r));
        assertFalse("对班表来说这天也不上班", WeekSchedule.isScheduledWorkday(FRI, r));
    }

    @Test
    public void leave_overridesNormalWorkday() {
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(FRI, rule(null)));
        assertEquals(WeekSchedule.DayType.LEAVE,
                WeekSchedule.dayType(FRI, rule(days(FRI, WeekSchedule.DAY_LEAVE))));
    }

    // ================= 加班 =================

    @Test
    public void overtime_givesRestDayAWorkTypeAndShift() {
        Shift night = shift(3, "夜班", 20 * 60);
        WeekSchedule.Rule r = rule(days(SAT, 3), night);

        assertEquals("休息日被覆盖成上班", WeekSchedule.DayType.WORK, WeekSchedule.dayType(SAT, r));
        assertTrue(WeekSchedule.isWorkday(SAT, r));
        assertEquals("这天要能说出上哪个班", night, WeekSchedule.shiftOn(SAT, r));
    }

    @Test
    public void overtime_outranksLegalHoliday() {
        assertEquals(WeekSchedule.DayType.HOLIDAY,
                WeekSchedule.dayType(NATIONAL_DAY, rule(null)));
        Shift day = shift(1, "白班", 8 * 60);
        WeekSchedule.Rule r = rule(days(NATIONAL_DAY, 1), day);

        assertEquals("国庆被叫去值班：用户说了算",
                WeekSchedule.DayType.WORK, WeekSchedule.dayType(NATIONAL_DAY, r));
        assertEquals(day, WeekSchedule.shiftOn(NATIONAL_DAY, r));
    }

    @Test
    public void overtimeWithoutShift_fallsBackToRule() {
        // 老数据迁过来的「加班但没指定班次」：类型仍按规则，闹钟走旧的兜底路径
        WeekSchedule.Rule r = rule(days(SAT, WeekSchedule.DAY_OVERTIME_ANY));

        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertNull(WeekSchedule.shiftOn(SAT, r));
        assertTrue("但它仍是一条加班记录", WeekSchedule.isScheduledWorkday(SAT, r));
    }

    @Test
    public void deletedShift_fallsBackToRule() {
        // 覆盖指向一个已被删掉的班次：落回规则，而不是把那天变成没有班的上班日
        WeekSchedule.Rule r = rule(days(SAT, 9), shift(1, "白班", 8 * 60));

        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertNull(WeekSchedule.shiftOn(SAT, r));
    }

    // ================= 没有覆盖 =================

    @Test
    public void untouchedDays_followTheRule() {
        WeekSchedule.Rule r = rule(days(SAT, WeekSchedule.DAY_LEAVE));

        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(FRI, r));
        assertEquals(WeekSchedule.DayType.LEAVE, WeekSchedule.dayType(SAT, r));
        assertEquals("相邻的周日不受影响", WeekSchedule.DayType.REST,
                WeekSchedule.dayType(SAT.plusDays(1), r));
    }

    @Test
    public void withDaysNull_clearsOverrides() {
        WeekSchedule.Rule marked = rule(days(FRI, WeekSchedule.DAY_LEAVE));
        assertEquals(WeekSchedule.DayType.LEAVE, WeekSchedule.dayType(FRI, marked));
        assertEquals("「按规则本来是什么」要能单独问",
                WeekSchedule.DayType.WORK, WeekSchedule.dayType(FRI, marked.withDays(null)));
    }

    // ================= 闹钟怎么读这张表 =================

    /**
     * 双休用户标「周六加班 · 夜班」，起床闹钟就该 20:00 响。
     *
     * <p>这条以前是错的：{@code timeOn} 只在「N 天一轮」模式下认班次，其它模式
     * 一律退回闹钟自己的 08:00——用户明明选了夜班，闹钟还在早上响。这也正是
     * 「8h / 12h 下班点不齐」那个缺口的根。v1.11.2 起班次只属于轮班，
     * 这条用例的班表也换成 7 天一轮。
     */
    @Test
    public void overtimeShift_drivesAlarmTime() {
        Shift night = shift(2, "夜班", 20 * 60);
        WeekSchedule.Rule r = rule(days(SAT, 2), night);
        Alarm a = new Alarm(1, 8, 0, "起床", Alarm.Mode.WORKDAY, true);

        assertTrue("这天要去上班", a.appliesTo(SAT, r, true));
        assertEquals("时刻跟班次走", 20 * 60, a.timeOn(SAT, r, true));
    }

    /** 工作日换成别的班，同样按那个班的时刻——「今天上夜班」不是一句备注。 */
    @Test
    public void shiftOverride_onWorkdayAlsoMovesAlarm() {
        Shift night = shift(2, "夜班", 22 * 60);
        WeekSchedule.Rule r = rule(days(FRI, 2), night);
        Alarm a = new Alarm(1, 8, 0, "起床", Alarm.Mode.WORKDAY, true);

        assertEquals(22 * 60, a.timeOn(FRI, r, true));
    }

    /**
     * 没有轮班表时，班次定义仍然留在规则里，但覆盖不生效（v1.11.1 立、v1.11.3 改口）。
     *
     * <p>v1.11.1 这条原本断言「shiftCycle 为 null 也要按 20:00 响」，理由是班次定义
     * 不该跟着轮班表一起丢。定义确实不该丢——但那不等于覆盖该生效：没有轮班表，
     * 就没有「几点上班」这一维，覆盖表里那个 id 只是一段没人认的残影。
     * 现在分开断言：定义在（{@code shiftById} 查得到），生效与否看
     * {@code hasShifts()}。
     */
    @Test
    public void shiftDefinitions_survive_withoutShiftCycle() {
        Shift night = shift(2, "夜班", 20 * 60);
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.CYCLE,
                null, false, 7, MON_FRI, CYCLE_START, NO_TIMES, false,
                null, new Shift[]{night}, days(SAT, 2), null);

        assertEquals("班次定义不随「有没有轮班表」一起丢", night, r.shiftById(2));
        assertFalse("但没有班次表就没有「几点上班」这一维", r.hasShifts());
        assertEquals("那天还是休息日", WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertNull("覆盖不成立", WeekSchedule.shiftOn(SAT, r));
    }

    /**
     * 只勾星期的「自定义」也没有班次（v1.11.3）。
     *
     * <p>{@code Mode.CYCLE} 装着两样东西：自定义（勾周一到周日 + 逐日时刻）和轮班
     * （N 天循环 + 班次表）。判断只看模式的话，自定义会被当成有班次，点休息日就冒出
     * 「上哪个班」——而它根本没有班可选。这条按自定义的真实形状构造：
     * {@code shiftCycle} 为 null、逐日时刻有值、班次表里有定义。
     */
    @Test
    public void customWeek_hasNoShifts() {
        Shift night = shift(2, "夜班", 20 * 60);
        int[] times = {8 * 60, 8 * 60, 8 * 60, 8 * 60, 8 * 60, -1, -1};
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.CYCLE,
                null, false, 7, MON_FRI, CYCLE_START, times, false,
                null, new Shift[]{night}, days(SAT, 2), new Shift[]{night});

        assertFalse("自定义是按周勾格子，没有班次", r.hasShifts());
        assertNull("所以覆盖里的班次 id 不算数", WeekSchedule.shiftOn(SAT, r));
        assertEquals("那天仍是休息日", WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals("逐日时刻照旧生效", 8 * 60, WeekSchedule.cycleTimeOf(FRI, r));
    }

    /**
     * 双休 / 单休 / 大小周：班次表里的定义一个都不生效（v1.11.2）。
     *
     * <p>从轮班切回双休时，班次定义和覆盖表里的 id 都还留着（切回去还要用）。
     * 但只要当前班表没有班次，它们就不该让日历写「夜班」、也不该改响铃时刻——
     * 否则用户看到的是一张双休班表上挂着一格夜班，而那天的闹钟真的按夜班响。
     */
    @Test
    public void doubleRest_ignoresShiftOverrides() {
        Shift night = shift(2, "夜班", 20 * 60);
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST,
                null, false, 0, 0L, null, null, false,
                null, new Shift[]{night}, days(SAT, 2), new Shift[]{night});

        assertNull("双休下没有班次，覆盖不成立", WeekSchedule.shiftOn(SAT, r));
        assertEquals("那天还是休息日", WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertNull("格子也不该写班次名", DayTag.shiftName(r, 2, 2));
        assertEquals("闹钟按自己的时刻，不跟着夜班走", 7 * 60,
                new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true).timeOn(SAT, r, true));
    }

    /** 请假日一律不响，连「每天」的闹钟也一样（由调用方按 vacation 跳过）。 */
    @Test
    public void leave_hasNoAlarmTime() {
        WeekSchedule.Rule r = rule(days(FRI, WeekSchedule.DAY_LEAVE));
        Alarm a = new Alarm(1, 8, 0, "起床", Alarm.Mode.WORKDAY, true);

        assertFalse("请假日不是上班日", a.appliesTo(FRI, r, false));
        assertEquals(Alarm.NO_TIME, a.timeOn(FRI, r, false));
    }
}
