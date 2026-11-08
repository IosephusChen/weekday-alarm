package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        // v1.23.12：要真的是轮班（铺了那张表），「取第一个班次」才有意义。
        // 双休 / 自定义七天没有班次，迁移只能记「未指定班次」
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setShiftCycle(ctx(), new int[]{2, 2, 2, 2, 2, -1, -1});
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
        // 正数 id 只在有班次的班表上算数（v1.11.2）：轮班模式 + 真的铺了轮班表，
        // 两个条件缺一不可。只切 mode=CYCLE 的话，这是「自定义七天」，没有班次
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        setShifts(new Shift(4, "白班", 8 * 60, Shift.END_UNKNOWN, 0));
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setShiftCycle(ctx(), new int[]{4, 4, 4, 4, 4, -1, -1});
        LocalDate d = LocalDate.of(2026, 11, 6);
        Prefs.addVacation(ctx(), d);
        assertTrue(Prefs.isVacation(ctx(), d));

        Prefs.setDayShift(ctx(), d, 4);   // 改成某个班次
        assertFalse(Prefs.isVacation(ctx(), d));
        assertTrue(Prefs.isOvertime(ctx(), d));
        assertEquals(Integer.valueOf(4), Prefs.getDayShift(ctx(), d));
    }

    /** 轮班 + 只有一个班次：直接用它，不必再问。 */
    @Test
    public void addOvertime_singleShift_usesItsId() {
        reset();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        setShifts(new Shift(7, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        // v1.23.12：只切模式不够——自定义也是 CYCLE，但不是轮班，没有班次可选。
        // 补上这张表，「只有一个班次就用它」这条便利才成立
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setShiftCycle(ctx(), new int[]{7, 7, 7, 7, 7, -1, -1});
        LocalDate d = LocalDate.of(2026, 11, 7);

        Prefs.addOvertime(ctx(), d);
        assertEquals("只有一个班次就别再问用户了", Integer.valueOf(7), Prefs.getDayShift(ctx(), d));
    }

    /**
     * 双休 + 表里还留着一个班次：仍然记 -2（v1.11.2）。
     *
     * <p>「只有一个班次就用它」这条便利只对轮班成立。双休没有班次，
     * 存成那个 id 会在日历上显示成「加班」但其实不生效——看着标上了，闹钟不管。
     */
    @Test
    public void addOvertime_onDoubleRest_ignoresLeftoverShifts() {
        reset();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        setShifts(new Shift(7, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        LocalDate d = LocalDate.of(2026, 11, 7);

        Prefs.addOvertime(ctx(), d);
        assertEquals("双休没有班次，记成「未指定班次」",
                Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY), Prefs.getDayShift(ctx(), d));
        assertTrue("但它仍算一条加班记录", Prefs.isOvertime(ctx(), d));
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

    /**
     * 双休 + 历史班次：表里查得到，但日历和闹钟都不认（v1.11.2）。
     *
     * <p>从轮班切回双休时，班次定义和覆盖表里的 id 都不删（切回去还要用）。
     * 只要当前班表没有班次，它们就不该让格子写「夜班」、也不该改响铃时刻。
     */
    @Test
    public void doubleRestWithShifts_ignoresThem() {
        reset();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        LocalDate sat = LocalDate.of(2026, 11, 7);   // 周六，本来休息
        Prefs.setDayShift(ctx(), sat, 3);

        WeekSchedule.Rule r = Prefs.getRule(ctx());
        assertEquals("班次定义仍留着，切回轮班还要用", "夜班", r.shiftById(3).name);
        assertFalse("但双休下它不算加班", Prefs.getOvertimeDates(ctx()).contains(sat));
        assertNull("日历不显示班次", WeekSchedule.shiftOn(sat, r));

        Alarm a = new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true);
        assertEquals("闹钟按自己的 07:00，不跟着夜班走", 7 * 60, a.timeOn(sat, r, true));
    }

    /** 同样一份数据，切回轮班就生效——不迁数据，只换模式。 */
    @Test
    public void sameShifts_becomeEffectiveInCycleMode() {
        reset();
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), 0b0011111L);                 // 周一到周五上班
        Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2)); // 周一
        // v1.11.3：生效的判据是「有没有这张轮班表」，所以要把表铺出来。
        // 只设 mode=CYCLE 不够——自定义也是 CYCLE，但没有班次。
        Prefs.setShiftCycle(ctx(), new int[]{3, 3, 3, 3, 3, -1, -1});
        LocalDate sat = LocalDate.of(2026, 11, 7);
        Prefs.setDayShift(ctx(), sat, 3);

        WeekSchedule.Rule r = Prefs.getRule(ctx());
        assertTrue("轮班下它是一条加班记录", Prefs.getOvertimeDates(ctx()).contains(sat));
        assertEquals("日历写「夜班」", "夜班", WeekSchedule.shiftOn(sat, r).name);
        assertEquals("闹钟按 20:00 响", 20 * 60,
                new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true).timeOn(sat, r, true));
    }

    /**
     * 切到按周再切回轮班，那张表原样还在（v1.11.3）。
     *
     * <p>这是这一版真正要修的东西：以前 {@code ScheduleDraft.commit} 切到按周时
     * 直接 {@code remove(shift_cycle)}，用户辛苦排的「上 12 休 2」就没了，切回来
     * 从「4 天、铺满第一个班次」重排。现在切成两份——生效的那份照旧清（留着会
     * 让按周态按班次算时刻），另存一份只做下次的默认值。
     */
    @Test
    public void rotationSurvivesRoundTripThroughWeekMode() {
        reset();
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));
        int[] rotation = {3, 3, -1, -1};                       // 上二休二
        Prefs.setShiftCycle(ctx(), rotation);

        ScheduleDraft draft = ScheduleDraft.load(ctx());
        // 周一到周五上班、周末休 —— 反推结果一定是双休
        draft.setDays(new boolean[]{true, true, true, true, true, false, false}, false);
        draft.commit(ctx());

        assertEquals("切到了按周", WeekSchedule.Mode.DOUBLE_REST, Prefs.getWeekMode(ctx()));
        assertNull("生效的那份照旧清掉", Prefs.getShiftCycle(ctx()));
        assertFalse("按周态没有班次", Prefs.getRule(ctx()).hasShifts());

        Prefs.Rotation bk = Prefs.getRotationBackup(ctx());
        assertNotNull("但那张表留了备份", bk);
        assertArrayEquals("一天不差", rotation, bk.cycle);
        assertEquals("起始日也留着", LocalDate.of(2026, 11, 2), bk.start);
    }

    /**
     * 切到轮班时，自定义里逐日填的时刻不再被删（v1.11.3）。
     *
     * <p>原来 {@code commit} 在轮班分支里 {@code setCycleTimes(null)}，理由是
     * 「两套数据都在会让人糊涂」。可轮班态压根不读它（{@code cycleTimeOf} 见
     * shiftCycle 非空就先返回），删了只剩副作用：从自定义切去轮班看一眼再切回来，
     * 七个时刻全没了。
     */
    @Test
    public void customTimes_surviveTripThroughRotation() {
        reset();
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));
        int[] times = {8 * 60, 9 * 60, 8 * 60, 8 * 60, 8 * 60, -1, -1};
        Prefs.setCycleTimes(ctx(), times);

        ScheduleDraft draft = ScheduleDraft.load(ctx());
        draft.setRotation(4, LocalDate.of(2026, 11, 2), new int[]{3, 3, -1, -1});
        draft.commit(ctx());

        // 链路上不删了，但读出来还差一步：getCycleTimes 要求长度等于当前周期，
        // 而此刻周期是 4 天。所以先看盘上，再把周期调回 7 天看能不能读出来。
        assertEquals("盘上还是那七个数",
                "480,540,480,480,480,-1,-1", sp().getString(Prefs.KEY_CYCLE_TIMES, null));
        Prefs.setCycleLen(ctx(), 7);
        assertArrayEquals("周期对上了就原样读回来", times, Prefs.getCycleTimes(ctx()));
    }

    /**
     * 班次被删掉之后，草稿里那个旧 id 不能再跟着 {@code commit()} 写回（v1.21.0）。
     *
     * <p>用户路径：正展开一张轮班表 → 点「管理班次」→ 删掉一个班次 → 点「完成」→ 保存。
     * {@code ShiftBookDialog.confirmDelete} 只清了盘上的 {@code shift_cycle}，草稿里
     * 那份旧数组还在，保存时原样写回，于是那天 {@code cycleMask} 说「上班」、
     * {@code shiftOn} 却找不到班次返回 null——日历显示上班，闹钟没有时刻。
     *
     * <p>修法是在 {@code commit()} 里按盘上的班次表剪一遍，所以这条用例只喂数据和草稿，
     * 不驱动弹窗：弹窗那一侧本来就没写错。
     */
    @Test
    public void commitDropsIdsOfDeletedShifts() {
        reset();
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0),
                new Shift(5, "白班", 8 * 60, 16 * 60, 0));
        LocalDate start = LocalDate.of(2026, 11, 2);

        ScheduleDraft draft = ScheduleDraft.load(ctx());
        // 四天：夜、夜、休、白 —— 第 4 天排的是 5 号
        draft.setRotation(4, start, new int[]{3, 3, -1, 5});
        // 用户在「管理班次」里把 5 号删掉（弹窗会把盘上的那份同步改掉）
        setShifts(new Shift(3, "夜班", 20 * 60, Shift.END_UNKNOWN, 0));

        draft.commit(ctx());

        assertArrayEquals("被删的那个 id 不许写回",
                new int[]{3, 3, -1, -1}, Prefs.getShiftCycle(ctx()));
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        assertTrue("第 1 天照旧上班", WeekSchedule.isWorkday(start, rule));
        assertFalse("第 4 天不该再算上班——班次没了，那天没有时刻",
                WeekSchedule.isWorkday(start.plusDays(3), rule));
        assertNull("那天也就没有班次",
                WeekSchedule.shiftOn(start.plusDays(3), rule));
    }
}
