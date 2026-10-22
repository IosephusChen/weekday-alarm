package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 睡前提醒（v1.20）。
 *
 * 覆盖三件最容易出错的事：
 * ① 触发时刻的边界（过点 / 恰好等于 / 未到点）；
 * ② 通知文案与「要不要发」的判断（上班 / 休息 / 请假 / 总开关关）；
 * ③ 通知里的「明天请假」是否真的把明天标掉了。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BedtimeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().apply();
    }

    private void putAlarms(List<Alarm> list) {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .apply();
    }

    /** 明天起 14 天内第一个上班日。 */
    private LocalDate findWorkday() {
        LocalDate anchor = Prefs.getAnchor(ctx());
        boolean smallRestSat = Prefs.isSmallRestSat(ctx());
        LocalDate d = LocalDate.now().plusDays(1);
        for (int i = 0; i < 14; i++, d = d.plusDays(1)) {
            if (WeekSchedule.isWorkday(d, anchor, smallRestSat)) return d;
        }
        throw new IllegalStateException("两周内找不到上班日");
    }

    /** 明天起 14 天内第一个休息日。 */
    private LocalDate findRestDay() {
        LocalDate anchor = Prefs.getAnchor(ctx());
        boolean smallRestSat = Prefs.isSmallRestSat(ctx());
        LocalDate d = LocalDate.now().plusDays(1);
        for (int i = 0; i < 14; i++, d = d.plusDays(1)) {
            if (!WeekSchedule.isWorkday(d, anchor, smallRestSat)) return d;
        }
        throw new IllegalStateException("两周内找不到休息日");
    }

    // ================= 触发时刻 =================

    @Test
    public void nextTrigger_beforeBedtime_isToday() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 21, 0);
        assertEquals(LocalDateTime.of(2026, 10, 20, 22, 0),
                BedtimeReminder.nextTrigger(ctx(), now));
    }

    @Test
    public void nextTrigger_afterBedtime_isTomorrow() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 22, 30);
        assertEquals(LocalDateTime.of(2026, 10, 21, 22, 0),
                BedtimeReminder.nextTrigger(ctx(), now));
    }

    @Test
    public void nextTrigger_exactlyAtBedtime_rollsToTomorrow() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        // 恰好等于：算明天。否则「设完提醒正好到点」会立刻弹一条，且当天可能弹两次
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 22, 0);
        assertEquals(LocalDateTime.of(2026, 10, 21, 22, 0),
                BedtimeReminder.nextTrigger(ctx(), now));
    }

    @Test
    public void nextTrigger_off_isNull() {
        Prefs.setBedtimeMinutes(ctx(), Prefs.BEDTIME_OFF);
        assertNull(BedtimeReminder.nextTrigger(ctx(), LocalDateTime.of(2026, 10, 20, 21, 0)));
    }

    @Test
    public void bedtimeMinutes_illegalValueFallsBackToOff() {
        Prefs.setBedtimeMinutes(ctx(), 24 * 60);
        assertEquals(Prefs.BEDTIME_OFF, Prefs.getBedtimeMinutes(ctx()));
        Prefs.setBedtimeMinutes(ctx(), 22 * 60 + 15);
        assertEquals(22 * 60 + 15, Prefs.getBedtimeMinutes(ctx()));
        assertEquals("22:15", BedtimeReminder.timeText(22 * 60 + 15));
        assertEquals("关闭", BedtimeReminder.timeText(Prefs.BEDTIME_OFF));
    }

    // ================= 通知内容 =================

    @Test
    public void model_workdayWithAlarm_pointsOutFirstAlarm() {
        LocalDate workday = findWorkday();
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 8, 0, "午休", Alarm.Mode.DAILY, true));
        list.add(new Alarm(2, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        putAlarms(list);

        BedtimeReminder.Model m = BedtimeReminder.computeModel(ctx(), workday);
        assertTrue(m.workday);
        assertFalse(m.vacation);
        assertTrue(m.hasAlarm);
        assertEquals("明天要上班", m.title);
        assertTrue("应报出最早的闹钟时间", m.text.contains("07:30"));
        assertTrue("应报出闹钟名称", m.text.contains("起床"));
        assertTrue(m.notify);
    }

    @Test
    public void model_restDay_isNotNotifiedByDefault() {
        LocalDate rest = findRestDay();
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        putAlarms(list);

        BedtimeReminder.Model m = BedtimeReminder.computeModel(ctx(), rest);
        assertFalse(m.workday);
        assertEquals("明天休息", m.title);
        assertTrue(m.text.contains("闹钟不会响"));
        assertFalse("默认只在上班前提醒，休息日不打扰", m.notify);

        // 关掉「仅上班时提醒」后，休息日也要说一声
        Prefs.setBedtimeWorkdayOnly(ctx(), false);
        assertTrue(BedtimeReminder.computeModel(ctx(), rest).notify);
    }

    @Test
    public void model_vacation_saysAlreadyOnLeave() {
        LocalDate workday = findWorkday();
        Prefs.addVacation(ctx(), workday);
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        putAlarms(list);

        BedtimeReminder.Model m = BedtimeReminder.computeModel(ctx(), workday);
        assertTrue(m.vacation);
        assertFalse(m.workday);
        assertEquals("明天已请假", m.title);
        assertFalse(m.notify);
    }

    @Test
    public void model_weekendOnlyAlarm_onWorkday_hasNoAlarm() {
        LocalDate workday = findWorkday();
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 9, 0, "周末", Alarm.Mode.WEEKEND, true));
        putAlarms(list);

        BedtimeReminder.Model m = BedtimeReminder.computeModel(ctx(), workday);
        assertFalse(m.hasAlarm);
        assertTrue(m.text.contains("没有会响的闹钟"));
    }

    @Test
    public void model_disabledAlarmIsIgnored() {
        LocalDate workday = findWorkday();
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, false));   // 单个闹钟关着
        putAlarms(list);

        assertFalse(BedtimeReminder.computeModel(ctx(), workday).hasAlarm);
    }

    // ================= 一键「明天请假」 =================

    @Test
    public void skipTomorrow_marksVacation() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        assertFalse(Prefs.isVacation(ctx(), tomorrow));
        new BedtimeReceiver().onReceive(ctx(),
                new Intent(BedtimeReceiver.ACTION_SKIP_TOMORROW));
        assertTrue("通知里的「明天请假」应把明天标为请假",
                Prefs.isVacation(ctx(), tomorrow));
    }

    @Test
    public void receiver_ignoresUnknownAction() {
        // 未知 action 不应有任何副作用（也不该崩）
        new BedtimeReceiver().onReceive(ctx(), new Intent("com.daxz.weekalarm.SOMETHING"));
        assertTrue(Prefs.getVacationDates(ctx()).isEmpty());
    }
}
