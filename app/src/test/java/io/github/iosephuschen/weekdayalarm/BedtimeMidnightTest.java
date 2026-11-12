package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowNotificationManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

/**
 * 睡前提醒的「午夜刷新」：排得出、撤得掉、明天休息时一条都不排（v0.59.0）。
 *
 * <p>只测 {@code refreshIfShown} 的文案不够——排程漏了，文案永远不会改口；
 * 撤销漏了，关掉提醒后系统里还留一条闹钟。这里直接看 {@link ShadowAlarmManager}。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BedtimeMidnightTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private ShadowAlarmManager am() {
        return shadowOf((AlarmManager) ctx().getSystemService(Context.ALARM_SERVICE));
    }

    private ShadowNotificationManager nm() {
        return shadowOf((NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE));
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        Prefs.setBedtimeWorkdayOnly(ctx(), true);
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleStart(ctx(), LocalDate.now());
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
    }

    private void seedAllWorkdaysWithAlarm() {
        Prefs.setCycleMask(ctx(), 0b1111111L);
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true)));
    }

    private void fireBedtime() {
        new BedtimeReceiver().onReceive(ctx(), new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_BEDTIME));
    }

    /** 按 action 找午夜刷新，不按 index——别的闹钟也可能在排。 */
    private ShadowAlarmManager.ScheduledAlarm findMidnight() {
        for (ShadowAlarmManager.ScheduledAlarm sa : am().getScheduledAlarms()) {
            PendingIntent pi = sa.operation;
            if (pi == null) continue;
            Intent i = shadowOf(pi).getSavedIntent();
            if (i != null && BedtimeReceiver.ACTION_MIDNIGHT.equals(i.getAction())) return sa;
        }
        return null;
    }

    /** 明天上班且有闹钟 → 通知发出，午夜刷新排到明天 00:00。 */
    @Test
    public void bedtime_schedulesMidnightRefresh() {
        seedAllWorkdaysWithAlarm();
        assertTrue("前提：明天要提醒",
                BedtimeReminder.computeModel(ctx(), LocalDate.now().plusDays(1)).notify);

        fireBedtime();

        assertNotNull("通知要发出来", nm().getNotification(BedtimeReminder.NOTIF_ID));
        ShadowAlarmManager.ScheduledAlarm midnight = findMidnight();
        assertNotNull("午夜刷新要排上", midnight);
        long expected = LocalDate.now().plusDays(1).atStartOfDay()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("午夜刷新必须正好落在明天 00:00", expected, midnight.triggerAtTime);
    }

    /** 撤提醒时要连午夜刷新一起撤，不能只撤通知。 */
    @Test
    public void cancelNotice_cancelsMidnightRefresh() {
        seedAllWorkdaysWithAlarm();
        fireBedtime();
        assertNotNull("前提：已排上", findMidnight());

        BedtimeReminder.cancelNotice(ctx());

        assertNull("cancelNotice 必须把午夜刷新撤掉", findMidnight());
        assertNull(nm().getNotification(BedtimeReminder.NOTIF_ID));
    }

    /** 明天休息 → 不发通知，也不排午夜刷新。 */
    @Test
    public void restTomorrow_postsNothingAndSchedulesNoMidnight() {
        Prefs.setCycleMask(ctx(), 0L);
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true)));
        assertFalse("前提：明天不该提醒",
                BedtimeReminder.computeModel(ctx(), LocalDate.now().plusDays(1)).notify);

        fireBedtime();

        assertNull("明天休息不该发通知", nm().getNotification(BedtimeReminder.NOTIF_ID));
        assertNull("明天休息也不该排午夜刷新", findMidnight());
    }
}
