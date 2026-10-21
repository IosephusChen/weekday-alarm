package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 个人请假（v1.14）：存储、日历联动、闹钟跳过。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class VacationTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void setupSingleDailyAlarm() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        java.util.List<Alarm> list = new java.util.ArrayList<>();
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));   // 每天响，便于数数
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putBoolean(Prefs.KEY_ENABLED, true)
                .putInt(Prefs.KEY_HORIZON, 7)   // 只看 7 天
                .apply();
    }

    @Test
    public void vacationPrefs_addRemovePersist() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        LocalDate d = LocalDate.now().plusDays(3);
        assertFalse(Prefs.isVacation(ctx(), d));
        Prefs.addVacation(ctx(), d);
        assertTrue(Prefs.isVacation(ctx(), d));
        assertEquals(1, Prefs.getVacationDates(ctx()).size());
        // 重复添加不重复计数
        Prefs.addVacation(ctx(), d);
        assertEquals(1, Prefs.getVacationDates(ctx()).size());
        Prefs.removeVacation(ctx(), d);
        assertFalse(Prefs.isVacation(ctx(), d));
        assertTrue(Prefs.getVacationDates(ctx()).isEmpty());
    }

    @Test
    public void vacationPrefs_corruptEntriesIgnored() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        Set<String> raw = new HashSet<>();
        raw.add("2026-10-21");
        raw.add("不是日期");
        raw.add("");
        sp.edit().putStringSet(Prefs.KEY_VACATION_DATES, raw).apply();
        Set<LocalDate> out = Prefs.getVacationDates(ctx());
        assertEquals(1, out.size());
        assertTrue(out.contains(LocalDate.of(2026, 10, 21)));
    }

    @Test
    public void rescheduleAll_skipsVacationDay() {
        setupSingleDailyAlarm();

        // 未标记前：先排一次建立基线（今天 7 点已过则基线少 1，不影响差值）
        int before = ScheduleManager.rescheduleAll(ctx());

        LocalDate vacDay = LocalDate.now().plusDays(2);
        Prefs.addVacation(ctx(), vacDay);
        // 标记后重排
        int after = ScheduleManager.rescheduleAll(ctx());

        assertEquals("请假日应从统计中减去", before - 1, after);
        // 登记记录里不应包含请假日
        assertFalse("请假日不应被登记",
                Prefs.getScheduledDates(ctx()).contains("1:" + vacDay));
        assertTrue(after > 0 && after <= ScheduleManager.MAX_REGISTERED_ALARMS);
    }

    @Test
    public void nextRegisteredAlarm_skipsVacationDay() {
        setupSingleDailyAlarm();
        // 把「今天」标记请假：下一个登记闹钟应直接是明天
        Prefs.addVacation(ctx(), LocalDate.now());
        ScheduleManager.rescheduleAll(ctx());

        ScheduleManager.NextAlarm next = ScheduleManager.nextRegisteredAlarm(ctx());
        assertTrue("今天请假但明天仍有闹钟，next 不应为 null", next != null);
        assertFalse("今天已请假，下一个闹钟不应落在今天",
                next.when.toLocalDate().equals(LocalDate.now()));
    }

    @Test
    public void cancelVacation_restoresAlarm() {
        setupSingleDailyAlarm();
        LocalDate vacDay = LocalDate.now().plusDays(2);
        Prefs.addVacation(ctx(), vacDay);
        ScheduleManager.rescheduleAll(ctx());
        int during = ScheduleManager.countScheduled(ctx());

        Prefs.removeVacation(ctx(), vacDay);
        ScheduleManager.rescheduleAll(ctx());
        int restored = ScheduleManager.countScheduled(ctx());

        assertEquals(during + 1, restored);
        assertTrue("取消请假后该日应重新登记",
                Prefs.getScheduledDates(ctx()).contains("1:" + vacDay));
    }

    @Test
    public void workdayAlarm_skipsVacationDay() {
        // 「仅上班日」闹钟同样受请假影响（请假日 = 不用上班）
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        java.util.List<Alarm> list = new java.util.ArrayList<>();
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putBoolean(Prefs.KEY_ENABLED, true)
                .putInt(Prefs.KEY_HORIZON, 90)
                .apply();

        // 找 horizon 内第一个上班日
        LocalDate anchor = Prefs.getAnchor(ctx());
        LocalDate workday = LocalDate.now();
        for (int i = 0; i < 30; i++) {
            if (WeekSchedule.isWorkday(workday, anchor, Prefs.isSmallRestSat(ctx()))
                    && !Prefs.isVacation(ctx(), workday)) break;
            workday = workday.plusDays(1);
        }
        Prefs.addVacation(ctx(), workday);
        ScheduleManager.rescheduleAll(ctx());
        assertFalse("请假的工作日不应被登记",
                Prefs.getScheduledDates(ctx()).contains("1:" + workday));
    }

    @Test
    public void mainActivity_withVacation_noCrash() {
        setupSingleDailyAlarm();
        Prefs.addVacation(ctx(), LocalDate.now().plusDays(1));
        // setup 覆盖 onCreate+onResume（含升级重排）；请假日不应导致任何异常
        Robolectric.buildActivity(MainActivity.class).setup();
    }
}
