package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 按天覆盖的存储与老数据迁移（v1.11.0）。
 *
 * <p>迁移这一段的代价最高：请假和加班是用户攒了一年多的记录，搬丢一天就是一次投诉。
 * 所以这几条测试盯的都是「搬完之后还是原来那几天」，以及「只搬一次」——
 * 后者更隐蔽：迁移标记没写对，改过的新日期会被老集合重新盖回去。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DayPlanPrefsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    private void reset() {
        sp().edit().clear().apply();
    }

    private void setShifts(Shift... shifts) {
        sp().edit().putString(Prefs.KEY_SHIFT_DEFS, Shift.toJson(Arrays.asList(shifts))).apply();
    }

    private void setLegacy(LocalDate[] vacations, LocalDate[] overtimes) {
        SharedPreferences.Editor ed = sp().edit();
        ed.putStringSet(Prefs.KEY_VACATION_DATES, toSet(vacations));
        ed.putStringSet(Prefs.KEY_OVERTIME_DATES, toSet(overtimes));
        ed.apply();
    }

    private static HashSet<String> toSet(LocalDate[] dates) {
        HashSet<String> s = new HashSet<>();
        for (LocalDate d : dates) s.add(d.toString());
        return s;
    }

    @Test
    public void migrate_keepsEveryDay() {
        reset();
        LocalDate a = LocalDate.of(2026, 11, 4);
        LocalDate b = LocalDate.of(2026, 11, 6);
        LocalDate c = LocalDate.of(2026, 11, 8);
        setLegacy(new LocalDate[]{a}, new LocalDate[]{b, c});

        assertTrue("请假日搬成 -1", Prefs.isVacation(ctx(), a));
        assertEquals(Integer.valueOf(WeekSchedule.DAY_LEAVE), Prefs.getDayShift(ctx(), a));
        // 一个班次都没建过：加班只能记「未指定班次」，行为与旧版一致
        assertTrue(Prefs.isOvertime(ctx(), b));
        assertEquals(Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY), Prefs.getDayShift(ctx(), b));
        assertEquals(3, Prefs.getDayPlan(ctx()).size());
    }

    @Test
    public void migrate_overtimeGetsFirstShift() {
        reset();
        setShifts(new Shift(2, "白班", 8 * 60, Shift.END_UNKNOWN, 0),
                new Shift(5, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        LocalDate d = LocalDate.of(2026, 11, 7);
        setLegacy(new LocalDate[]{}, new LocalDate[]{d});

        assertEquals("老版本没记是哪个班，取第一个——与旧行为一致",
                Integer.valueOf(2), Prefs.getDayShift(ctx(), d));
    }

    @Test
    public void migrate_sameDay_bothMarked_leaveWins() {
        reset();
        LocalDate d = LocalDate.of(2026, 11, 5);
        setLegacy(new LocalDate[]{d}, new LocalDate[]{d});

        assertEquals("旧版允许「假加」，迁移时按日历的显示口径取请假",
                Integer.valueOf(WeekSchedule.DAY_LEAVE), Prefs.getDayShift(ctx(), d));
        assertTrue(Prefs.isVacation(ctx(), d));
        assertFalse(Prefs.isOvertime(ctx(), d));
    }

    @Test
    public void migrate_runsOnlyOnce() {
        reset();
        LocalDate migrated = LocalDate.of(2026, 11, 4);
        setLegacy(new LocalDate[]{migrated}, new LocalDate[]{});

        // 第一次读触发迁移
        assertTrue(Prefs.isVacation(ctx(), migrated));

        // 迁移后在新表里改一天：取消旧的、加一个新的
        LocalDate added = LocalDate.of(2026, 11, 9);
        Prefs.clearDayShift(ctx(), migrated);
        Prefs.setDayShift(ctx(), added, WeekSchedule.DAY_LEAVE);

        // 再读不能把老集合又搬回来
        assertFalse("迁移只能跑一次", Prefs.isVacation(ctx(), migrated));
        assertTrue(Prefs.isVacation(ctx(), added));
        assertEquals(1, Prefs.getDayPlan(ctx()).size());
    }

    @Test
    public void setDayShift_overwritesSameDay() {
        reset();
        LocalDate d = LocalDate.of(2026, 11, 6);
        Prefs.addVacation(ctx(), d);
        assertTrue(Prefs.isVacation(ctx(), d));

        Prefs.setDayShift(ctx(), d, 4);   // 改成某个班次
        assertFalse(Prefs.isVacation(ctx(), d));
        assertTrue(Prefs.isOvertime(ctx(), d));
        assertEquals(Integer.valueOf(4), Prefs.getDayShift(ctx(), d));
    }

    @Test
    public void addOvertime_singleShift_usesItsId() {
        reset();
        setShifts(new Shift(7, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        LocalDate d = LocalDate.of(2026, 11, 7);

        Prefs.addOvertime(ctx(), d);
        assertEquals("只有一个班次就别再问用户了", Integer.valueOf(7), Prefs.getDayShift(ctx(), d));
    }

    @Test
    public void setVacationDates_replacesOnlyLeaveDays() {
        reset();
        LocalDate leave = LocalDate.of(2026, 11, 4);
        LocalDate ot = LocalDate.of(2026, 11, 6);
        Prefs.addVacation(ctx(), leave);
        Prefs.addOvertime(ctx(), ot);

        LocalDate other = LocalDate.of(2026, 11, 10);
        Prefs.setVacationDates(ctx(), new HashSet<>(Arrays.asList(other)));

        assertFalse("旧请假日被整体替换掉", Prefs.isVacation(ctx(), leave));
        assertTrue(Prefs.isVacation(ctx(), other));
        assertTrue("加班项不受影响", Prefs.isOvertime(ctx(), ot));
    }

    @Test
    public void getDayPlan_ignoresBrokenEntries() {
        reset();
        LocalDate good = LocalDate.of(2026, 11, 4);
        sp().edit().putString(Prefs.KEY_DAY_PLAN,
                "{\"2026-11-04\":-1,\"不是日期\":3,\"2026-13-40\":1}").apply();
        sp().edit().putBoolean(Prefs.KEY_DAY_PLAN_MIGRATED, true).apply();

        Map<LocalDate, Integer> plan = Prefs.getDayPlan(ctx());
        assertEquals("坏条目只丢它自己", 1, plan.size());
        assertEquals(Integer.valueOf(WeekSchedule.DAY_LEAVE), plan.get(good));
    }

    @Test
    public void emptyPlan_readsAsNoExceptions() {
        reset();
        assertTrue(Prefs.getDayPlan(ctx()).isEmpty());
        assertNull(Prefs.getDayShift(ctx(), LocalDate.now()));
        assertFalse(Prefs.isVacation(ctx(), LocalDate.now()));
        assertFalse(Prefs.isOvertime(ctx(), LocalDate.now()));
    }

    // ================= 首页按钮怎么读这张表 =================

    /**
     * 工作日记「这天上了夜班」不等于请假。
     *
     * <p>首页那个按钮的文案由 {@link DayOffAction#isMarked} 决定。只判「表里有没有值」
     * 的话，工作日记一笔加班，按钮就变成「今天已请假」——用户点一下还以为在取消请假。
     */
    @Test
    public void workdayShiftRecord_isNotTreatedAsLeave() {
        reset();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        LocalDate fri = LocalDate.of(2026, 11, 6);   // 周五
        Prefs.setDayShift(ctx(), fri, 3);

        assertFalse("记了上夜班，但不是请假", DayOffAction.isMarked(ctx(), fri));
        assertTrue("按钮点下去应标成请假，而不是把这笔记录删掉",
                DayOffAction.toggle(ctx(), fri));
        assertEquals("请假覆盖掉原来的班次记录",
                Integer.valueOf(WeekSchedule.DAY_LEAVE), Prefs.getDayShift(ctx(), fri));
    }

    /** 休息日标了加班，按钮显示「已加班」；再点一次清掉。 */
    @Test
    public void restDayOvertime_isMarked_andTogglesOff() {
        reset();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        LocalDate sat = LocalDate.of(2026, 11, 7);   // 周六

        assertTrue(DayOffAction.toggle(ctx(), sat));
        assertTrue(DayOffAction.isMarked(ctx(), sat));
        assertFalse(DayOffAction.toggle(ctx(), sat));
        assertFalse(DayOffAction.isMarked(ctx(), sat));
        assertNull(Prefs.getDayShift(ctx(), sat));
    }
}
