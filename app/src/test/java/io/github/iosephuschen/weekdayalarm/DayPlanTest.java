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

    /** 双休 + 一张覆盖表 + 若干班次。 */
    private static WeekSchedule.Rule rule(Map<LocalDate, Integer> days, Shift... shifts) {
        return new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false,
                0, 0L, null, null, false, null, null,
                days, shifts.length == 0 ? null : shifts);
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
     * <p>这条以前是错的：{@code timeOn} 只在「N 天一轮」模式下认班次，双休 / 大小周
     * 一律退回闹钟自己的 08:00——用户明明选了夜班，闹钟还在早上响。这也正是
     * 「8h / 12h 下班点不齐」那个缺口的根。
     */
    @Test
    public void overtimeShift_drivesAlarmTime_outsideCycleMode() {
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
     * 没有轮班表时，全局班次表也要活着（v1.11.1）。
     *
     * <p>上面两条用 {@link #rule} 把班次塞进 {@code dayShifts}（第 13 个参数），
     * {@code shiftById} 靠兜底表查得到，于是「构造函数一见 shiftCycle 为 null
     * 就把 shifts 置空」这个缺陷被遮住了。{@code Prefs.getRule} 现在正好也把全局表
     * 传给 dayShifts，所以真机上还没露头；但正确性不该挂在这张兜底表上——
     * 任何只传全局表的调用点都会让双休用户的班次凭空消失。
     * 这条按 {@code Prefs.getRule} 的形状构造：shiftCycle 为 null、shifts 有值、dayShifts 为 null。
     */
    @Test
    public void globalShiftTable_survivesWithoutShiftCycle() {
        Shift night = shift(2, "夜班", 20 * 60);
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST,
                null, false, 0, 0L, null, null, false,
                null, new Shift[]{night}, days(SAT, 2), null);

        assertEquals("班次表不随「有没有轮班」一起丢", night, r.shiftById(2));
        assertEquals("标了夜班，时刻就该是 20:00", 20 * 60,
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
