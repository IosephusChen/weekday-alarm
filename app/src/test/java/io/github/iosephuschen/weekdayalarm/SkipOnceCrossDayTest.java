package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * B2：跨天实例的「这次不响」必须按响铃日记账。
 *
 * <p>班次 00:00、提前 60 分钟 → 实例响在前一天 23:00。D 日 22:30 点「这次不响」，
 * 通知写的是响铃日 D，重排却按逻辑日 D+1 查，于是又把它登记回来，23:00 照响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SkipOnceCrossDayTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 10);
    private static final int ALARM_ID = 9;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 22, 30);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void seedMidnightShift() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 1);
        Prefs.setCycleStart(ctx(), D);
        Prefs.setShifts(ctx(), Collections.singletonList(
                new Shift(1, "零点班", 0, Shift.END_UNKNOWN, 0)));
        Prefs.setShiftCycle(ctx(), new int[]{1});
        Prefs.setHorizonDays(ctx(), 3);
        Prefs.setPreNoticeMinutes(ctx(), 0);

        Alarm a = new Alarm(ALARM_ID, 8, 0, "提前一小时", Alarm.Mode.WORKDAY, true);
        a.shiftIds = new int[]{1};
        a.offsetMin = -60;
        Prefs.setAlarms(ctx(), Collections.singletonList(a));
    }

    @Test
    public void skipOnce_writtenForRingDate_preventsReRegistration() {
        seedMidnightShift();
        ScheduleManager.rescheduleAll(ctx(), NOW);
        assertTrue("D 日 22:30 时应已登记 D 23:00",
                ScheduleManager.isAlarmRegistered(ctx(), ALARM_ID, D));

        Prefs.addSkipOnce(ctx(), ALARM_ID, D);   // 通知记的是响铃日
        ScheduleManager.rescheduleAll(ctx(), NOW);

        assertFalse("这次不响被当成逻辑日，实例又复活了",
                ScheduleManager.isAlarmRegistered(ctx(), ALARM_ID, D));
        assertTrue("只跳过这一轮，次日 23:00 仍应登记",
                ScheduleManager.isAlarmRegistered(ctx(), ALARM_ID, D.plusDays(1)));
    }

    @Test
    public void nextRing_skipsTheCancelledCrossDayInstance() {
        seedMidnightShift();
        Prefs.addSkipOnce(ctx(), ALARM_ID, D);
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(Prefs.getAlarms(ctx()),
                Prefs.getRule(ctx()), Prefs.getVacationDates(ctx()),
                Prefs.getOvertimeDates(ctx()), Prefs.getSkipOnce(ctx()), NOW, 3);

        assertNotNull(next);
        assertEquals(LocalDateTime.of(2026, 11, 11, 23, 0), next.when);
    }

    @Test
    public void countCandidates_doesNotCountTheCancelledInstance() {
        seedMidnightShift();
        Prefs.addSkipOnce(ctx(), ALARM_ID, D);
        assertEquals("D 23:00 已取消，窗口内只剩 D+1 23:00 一次",
                1, ScheduleManager.countCandidates(ctx(), Prefs.getAlarms(ctx()), NOW));
    }
}
