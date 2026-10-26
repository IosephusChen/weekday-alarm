package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 个人加班（v0.13.0）：存储、日历联动、闹钟补响。
 * 加班是请假的镜像——休息日标记后「仅上班日」闹钟当天照响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class OvertimeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 单个「仅上班日」闹钟 + 30 天窗口，便于数数。 */
    private void setupSingleWorkdayAlarm() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        java.util.List<Alarm> list = new java.util.ArrayList<>();
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putInt(Prefs.KEY_HORIZON, 30)
                .apply();
    }

    /** 找最近的一个休息日（大小周休或法定节假日；至少每周都有一个）。 */
    private LocalDate findRestDay() {
        LocalDate anchor = Prefs.getAnchor(ctx());
        boolean smallRestSat = Prefs.isSmallRestSat(ctx());
        LocalDate d = LocalDate.now().plusDays(1);
        for (int i = 0; i < 30; i++) {
            if (!WeekSchedule.isWorkday(d, anchor, smallRestSat)) return d;
            d = d.plusDays(1);
        }
        throw new IllegalStateException("30 天内找不到休息日");
    }

    @Test
    public void overtimePrefs_addRemovePersist() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        LocalDate d = LocalDate.now().plusDays(3);
        assertFalse(Prefs.isOvertime(ctx(), d));
        Prefs.addOvertime(ctx(), d);
        assertTrue(Prefs.isOvertime(ctx(), d));
        assertEquals(1, Prefs.getOvertimeDates(ctx()).size());
        Prefs.addOvertime(ctx(), d);   // 重复添加不重复计数
        assertEquals(1, Prefs.getOvertimeDates(ctx()).size());
        Prefs.removeOvertime(ctx(), d);
        assertFalse(Prefs.isOvertime(ctx(), d));
        assertTrue(Prefs.getOvertimeDates(ctx()).isEmpty());
    }

    @Test
    public void overtimePrefs_corruptEntriesIgnored() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        Set<String> raw = new HashSet<>();
        raw.add("2026-10-25");
        raw.add("不是日期");
        raw.add("");
        sp.edit().putStringSet(Prefs.KEY_OVERTIME_DATES, raw).apply();
        Set<LocalDate> out = Prefs.getOvertimeDates(ctx());
        assertEquals(1, out.size());
        assertTrue(out.contains(LocalDate.of(2026, 10, 25)));
    }

    @Test
    public void appliesTo_overtimeMakesWorkdayAlarmRing() {
        setupSingleWorkdayAlarm();
        LocalDate restDay = findRestDay();
        LocalDate anchor = Prefs.getAnchor(ctx());
        boolean smallRestSat = Prefs.isSmallRestSat(ctx());

        Alarm work = new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true);
        assertFalse("休息日本来不响", work.appliesTo(restDay, anchor, smallRestSat));
        assertTrue("标记加班后应响", work.appliesTo(restDay, anchor, smallRestSat, true));

        // 「每天」闹钟不受加班影响（本来就响）；上班日无加班标记也不受影响
        Alarm daily = new Alarm(2, 12, 30, "午休", Alarm.Mode.DAILY, true);
        assertTrue(daily.appliesTo(restDay, anchor, smallRestSat, true));
        assertTrue(work.appliesTo(restDay.plusDays(0), anchor, smallRestSat, false)
                == WeekSchedule.isWorkday(restDay, anchor, smallRestSat));
    }

    @Test
    public void rescheduleAll_schedulesWorkdayAlarmOnOvertimeRestDay() {
        setupSingleWorkdayAlarm();
        LocalDate restDay = findRestDay();
        int before = ScheduleManager.rescheduleAll(ctx());

        Prefs.addOvertime(ctx(), restDay);
        int after = ScheduleManager.rescheduleAll(ctx());

        assertEquals("加班日应多排一个「仅上班日」闹钟", before + 1, after);
        assertTrue("加班日应被登记",
                Prefs.getScheduledDates(ctx()).contains("1:" + restDay));
    }

    @Test
    public void cancelOvertime_restoresRest() {
        setupSingleWorkdayAlarm();
        LocalDate restDay = findRestDay();
        Prefs.addOvertime(ctx(), restDay);
        ScheduleManager.rescheduleAll(ctx());
        int during = ScheduleManager.countScheduled(ctx());

        Prefs.removeOvertime(ctx(), restDay);
        ScheduleManager.rescheduleAll(ctx());
        int restored = ScheduleManager.countScheduled(ctx());

        assertEquals(during - 1, restored);
        assertFalse("取消加班后该日不应再登记",
                Prefs.getScheduledDates(ctx()).contains("1:" + restDay));
    }

    @Test
    public void isEffectiveWorkday_countsOvertimeRestDay() {
        setupSingleWorkdayAlarm();
        LocalDate restDay = findRestDay();
        assertFalse(ScheduleManager.isEffectiveWorkday(ctx(), restDay));
        Prefs.addOvertime(ctx(), restDay);
        assertTrue(ScheduleManager.isEffectiveWorkday(ctx(), restDay));
    }

    @Test
    public void vacationWins_ifBothMarked() {
        // 数据层面同时标了请假与加班（UI 入口互斥，不会发生）：请假优先，不排
        setupSingleWorkdayAlarm();
        LocalDate restDay = findRestDay();
        int before = ScheduleManager.rescheduleAll(ctx());

        Prefs.addVacation(ctx(), restDay);
        Prefs.addOvertime(ctx(), restDay);
        int after = ScheduleManager.rescheduleAll(ctx());

        assertEquals(before, after);
        assertFalse("请假优先于加班", Prefs.getScheduledDates(ctx()).contains("1:" + restDay));
    }

    /**
     * v0.13.1 图例回归：7 个语义项必须分两行，且全部可见（单行时「加班」「今天」会被挤出屏幕）。
     * v0.23.0 起两行统一为 4 列网格：第二行只有 3 项，第 4 格是不可见占位，
     * 保证两行列宽一致、色块左边缘对齐（旧版 4+3 各按 weight 均分，列宽 1/4 与 1/3 不同，整体错位）。
     */
    @Test
    public void legend_twoRows_allItemsVisible() {
        setupSingleWorkdayAlarm();
        Prefs.setOnboardDone(ctx(), true);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        LinearLayout legend = activity.findViewById(R.id.legend_row);

        assertEquals("图例应为两行", 2, legend.getChildCount());
        LinearLayout row1 = (LinearLayout) legend.getChildAt(0);
        LinearLayout row2 = (LinearLayout) legend.getChildAt(1);
        assertEquals(4, row1.getChildCount());   // 上班/休息/节假日/调休补班
        assertEquals(4, row2.getChildCount());   // 请假/加班/今天 + 占位

        // 每项 = 色块 + 文字；收集全部文字，7 个语义一个都不能少
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (LinearLayout row : new LinearLayout[]{row1, row2}) {
            for (int i = 0; i < row.getChildCount(); i++) {
                android.view.View child = row.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;   // 第二行第 4 格：占位 View
                LinearLayout item = (LinearLayout) child;
                assertEquals("每项应为 色块+文字", 2, item.getChildCount());
                // 所有格子等宽（weight=1）且左对齐，两行才能严格对齐
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) item.getLayoutParams();
                assertEquals("图例每列应等宽均分", 1f, lp.weight, 0.001f);
                // START 在布局解析后会落成 LEFT，两者等价
                int hGravity = item.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
                assertTrue("图例每格应左对齐，实际=" + hGravity,
                        hGravity == Gravity.LEFT || hGravity == Gravity.START);
                labels.add(((TextView) item.getChildAt(1)).getText().toString());
            }
        }
        assertTrue(labels.contains("上班"));
        assertTrue(labels.contains("休息"));
        assertTrue(labels.contains("节假日"));
        assertTrue(labels.contains("调休补班"));
        assertTrue(labels.contains("请假"));
        assertTrue(labels.contains("加班"));
        assertTrue(labels.contains("今天"));
        assertEquals(7, labels.size());
    }
}
