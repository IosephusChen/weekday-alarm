package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 班次模型（v1.8.0）。
 *
 * <p>这一版要回答的是「明天我上夜班」，因此时刻不再是闹钟自己的属性，而是
 * <b>班次开始时刻 + 偏移</b>。下面每一条都在钉这个换算，以及三个边界：
 * 休息日不响、只勾白班的闹钟夜班那天不响、跨午夜靠 plusMinutes 而不是特殊编码。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ShiftScheduleTest {

    /** 第 1 天。挑周一只是为了让「上 2 休 2」跨周末，读起来直观。 */
    private static final LocalDate START = LocalDate.of(2026, 11, 2);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** 白班 08:00–20:00、夜班 20:00–次日 08:00。 */
    private static List<Shift> twoShifts() {
        List<Shift> list = new ArrayList<>();
        list.add(new Shift(1, "白班", 8 * 60, 20 * 60, 0));
        list.add(new Shift(2, "夜班", 20 * 60, 8 * 60, 1));
        return list;
    }

    /** 4 天一轮：白 / 夜 / 休 / 休。 */
    private static WeekSchedule.Rule bookRule() {
        return new WeekSchedule.Rule(WeekSchedule.Mode.CYCLE, null, false,
                4, 0b0011L, START, null, false,
                new int[]{1, 2, -1, -1},
                twoShifts().toArray(new Shift[0]));
    }

    private static Alarm alarm(int[] ids, int offset) {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.shiftIds = ids;
        a.offsetMin = offset;
        return a;
    }

    // ================= 时刻换算 =================

    @Test
    public void startMinusNinety_readsEachShift() {
        Alarm a = alarm(new int[]{1, 2}, -90);
        WeekSchedule.Rule g = bookRule();
        assertEquals("06:30", TimeText.clockOfMinutes(a.timeOn(START, g)));
        assertEquals("18:30", TimeText.clockOfMinutes(a.timeOn(START.plusDays(1), g)));
        assertEquals("休息日不响", Alarm.NO_TIME, a.timeOn(START.plusDays(2), g));
        assertEquals(Alarm.NO_TIME, a.timeOn(START.plusDays(3), g));
    }

    @Test
    public void onlyWhiteShift_doesNotRingOnNight() {
        // 午休结束只挂白班：夜班那天它压根不参与
        Alarm lunch = alarm(new int[]{1}, 270);
        WeekSchedule.Rule g = bookRule();
        assertEquals("白班 12:30", "12:30", TimeText.clockOfMinutes(lunch.timeOn(START, g)));
        assertEquals("夜班那天不响", Alarm.NO_TIME, lunch.timeOn(START.plusDays(1), g));
        assertFalse("appliesTo 也要拦住，不能只靠 timeOn",
                lunch.appliesTo(START.plusDays(1), g, false));
        assertTrue(lunch.appliesTo(START, g, false));
    }

    @Test
    public void afterTwelveHours_crossesMidnight() {
        Alarm off = alarm(new int[]{1, 2}, 720);
        WeekSchedule.Rule g = bookRule();
        assertEquals("白班 20:00", "20:00", TimeText.clockOfMinutes(off.timeOn(START, g)));
        // 夜班 20:00 开始后 12 小时 = 次日 08:00。timeOn 返回 1920，显示绕回 08:00
        assertEquals(1920, off.timeOn(START.plusDays(1), g));
        assertEquals("08:00", TimeText.clockOfMinutes(off.timeOn(START.plusDays(1), g)));
    }

    @Test
    public void beforeMidnight_goesToPreviousDay() {
        // 班次 00:30 开始、提前 90 分 → 前一天 23:00，timeOn 是负数
        WeekSchedule.Rule g = new WeekSchedule.Rule(WeekSchedule.Mode.CYCLE, null, false,
                1, 0b1L, START, null, false,
                new int[]{9}, new Shift[]{new Shift(9, "早班", 30, 9 * 60, 0)});
        Alarm a = alarm(new int[]{9}, -90);
        assertEquals(-60, a.timeOn(START, g));
        assertEquals("23:00", TimeText.clockOfMinutes(a.timeOn(START, g)));
    }

    @Test
    public void overtimeRestDay_fallsBackToFirstShift() {
        // 休息日标了加班：没有班次可查，退到第一个勾选的班次
        Alarm a = alarm(new int[]{1, 2}, -60);
        WeekSchedule.Rule g = bookRule();
        assertEquals(Alarm.NO_TIME, a.timeOn(START.plusDays(2), g, false));
        assertEquals(7 * 60, a.timeOn(START.plusDays(2), g, true));   // 白班 08:00 提前 1 小时
        assertTrue(a.appliesTo(START.plusDays(2), g, true));
    }

    // ================= 数据层 =================

    @Test
    public void setShiftCycle_syncsWorkdayMask() {
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setShiftCycle(ctx(), new int[]{1, 2, -1, -1});

        assertEquals(0b0011L, Prefs.getCycleMask(ctx()));
        int[] saved = Prefs.getShiftCycle(ctx());
        org.junit.Assert.assertArrayEquals(new int[]{1, 2, -1, -1}, saved);
    }

    @Test
    public void shiftCycle_wrongLength_isIgnored() {
        Prefs.setCycleLen(ctx(), 4);
        // 手工改坏成 3 个数：宁可当没配，也不要拿它去索引 4 天的表
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                .putString(Prefs.KEY_SHIFT_CYCLE, "1,2,-1").commit();
        assertNull(Prefs.getShiftCycle(ctx()));
    }

    @Test
    public void shiftJson_roundTrip() {
        String json = Shift.toJson(twoShifts());
        List<Shift> back = Shift.fromJson(json);
        assertEquals(2, back.size());
        assertEquals("夜班", back.get(1).name);
        assertEquals(20 * 60, back.get(1).start);
        assertTrue(back.get(1).crossesMidnight());
        assertEquals("20:00 – 次日 08:00", back.get(1).spanText());
    }

    @Test
    public void unknownEnd_writesStartOnly() {
        Shift s = new Shift(3, "值班", 9 * 60, Shift.END_UNKNOWN, 0);
        assertEquals("09:00 起", s.spanText());
        assertFalse(s.crossesMidnight());
        assertEquals(-1, s.durationMinutes());
    }

    @Test
    public void alarmJson_keepsShiftLink() {
        Alarm a = alarm(new int[]{1, 2}, -90);
        String json = Alarm.toJson(Arrays.asList(a));
        Alarm back = Alarm.fromJson(json).get(0);
        assertEquals(2, back.shiftIds.length);
        assertEquals(-90, back.offsetMin);
    }

    @Test
    public void alarmJson_oldBackupWithoutShiftLink_stillReads() {
        String legacy = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\",\"mode\":0,\"enabled\":true}]";
        Alarm a = Alarm.fromJson(legacy).get(0);
        assertNull(a.shiftIds);
        assertEquals(0, a.offsetMin);
        // 没有班次表时退回自己的时刻，行为与 v1.7 一致
        assertEquals(450, a.timeOn(START, WeekSchedule.Rule.bigSmall(START, false)));
    }

    // ================= 摘要 =================

    @Test
    public void cycleSummary_countsEachShift() {
        assertEquals("共 4 天 · 白班 1 · 夜班 1 · 休 2",
                ShiftCycleSheet.summaryOf(new int[]{1, 2, -1, -1}, twoShifts()));
    }

    /** 冒烟：轮班态下「班次」那一行要真的显示出来，不能在 inflate 或 render 上崩。 */
    @Test
    public void weekScheduleActivity_showsShiftRow() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), START);
        Prefs.setShifts(ctx(), twoShifts());
        Prefs.setShiftCycle(ctx(), new int[]{1, 2, -1, -1});

        WeekScheduleActivity a = org.robolectric.Robolectric
                .buildActivity(WeekScheduleActivity.class).setup().get();
        android.view.View row = a.findViewById(R.id.row_shifts);
        android.widget.TextView sub = a.findViewById(R.id.tv_shifts_sub);
        assertEquals(android.view.View.VISIBLE, row.getVisibility());
        assertTrue("应列出班次名，实际：" + sub.getText(), sub.getText().toString().contains("白班"));
        assertTrue("整轮形状要写出来", a.findViewById(R.id.tv_rotation_times)
                .getVisibility() == android.view.View.VISIBLE);
    }

    @Test
    public void offsetText_speaksHuman() {
        assertEquals("开始前 1 小时 30 分", ShiftPickDialog.offsetText(-90));
        assertEquals("开始后 4 小时", ShiftPickDialog.offsetText(240));
        assertEquals("班次开始那一刻", ShiftPickDialog.offsetText(0));
    }

    /**
     * 切回「双休 / 大小周」之后不能再按班次算（v1.8.1）。
     *
     * <p>`shift_cycle` 和 `shift_defs` 切回去时没人清，全留在 Prefs 里。不挡的话
     * `cycleIndex` 会拿旧的 N 天表取班次，于是「今天明明双休休息」，
     * 闹钟还按「白班 08:00 + 偏移」响。这条守住 mode 这一道闸。
     */
    @Test
    public void backToWeekly_ignoresLeftoverShifts() {
        WeekSchedule.Rule weekly = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false,
                4, 0b0011L, START, null, false,
                new int[]{1, 2, -1, -1},
                twoShifts().toArray(new Shift[0]));

        assertNull("按周排时 shiftOn 必须返回 null", WeekSchedule.shiftOn(START, weekly));
        Alarm a = alarm(new int[]{1, 2}, 0);
        // 07:30 自己的时刻，不是白班 08:00
        assertEquals(7 * 60 + 30, a.timeOn(START, weekly));
    }
}
