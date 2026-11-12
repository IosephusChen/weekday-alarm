package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
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
 * B1：班次开始后的偏移把实例推到次日，次日零点后的任何一次重排都必须把它留住。
 *
 * <p>夜班 20:00 + 720 分钟 = 次日 08:00。旧实现从「今天」起算候选，
 * D+1 00:30 重排时从 D+1 起算，D+1 08:00 既不在窗口内、又被 {@code cancelAll}
 * 先撤掉，于是该次不响。这里把重排用的「现在」固定住，才能稳定复现。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CrossDayShiftRescheduleTest {

    /** 2026-11-10 周二，作为班表的第 1 天。 */
    private static final LocalDate D = LocalDate.of(2026, 11, 10);
    private static final int ALARM_ID = 7;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void seedNightShift() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 1);
        Prefs.setCycleStart(ctx(), D);
        Prefs.setShifts(ctx(), Collections.singletonList(
                new Shift(1, "夜班", 20 * 60, Shift.END_UNKNOWN, 0)));
        Prefs.setShiftCycle(ctx(), new int[]{1});
        Prefs.setHorizonDays(ctx(), 30);
        Prefs.setPreNoticeMinutes(ctx(), 0);

        Alarm a = new Alarm(ALARM_ID, 8, 0, "夜班后", Alarm.Mode.WORKDAY, true);
        a.shiftIds = new int[]{1};
        a.offsetMin = 720;   // 20:00 + 12 小时 = 次日 08:00
        Prefs.setAlarms(ctx(), Collections.singletonList(a));
    }

    @Test
    public void reschedule_afterMidnight_keepsNextMorningInstance() {
        seedNightShift();
        ScheduleManager.rescheduleAll(ctx(), LocalDateTime.of(2026, 11, 10, 20, 30));
        assertTrue("D 日 20:30 重排后，D+1 08:00 应已登记",
                ScheduleManager.isAlarmRegistered(ctx(), ALARM_ID, D.plusDays(1)));

        ScheduleManager.rescheduleAll(ctx(), LocalDateTime.of(2026, 11, 11, 0, 30));
        assertTrue("D+1 00:30 重排不能把 D+1 08:00 抹掉",
                ScheduleManager.isAlarmRegistered(ctx(), ALARM_ID, D.plusDays(1)));
    }

    @Test
    public void nextRing_afterMidnight_pointsAtNextMorningInstance() {
        seedNightShift();
        List<Alarm> alarms = Prefs.getAlarms(ctx());
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(alarms, Prefs.getRule(ctx()),
                Prefs.getVacationDates(ctx()), Prefs.getOvertimeDates(ctx()),
                Prefs.getSkipOnce(ctx()), LocalDateTime.of(2026, 11, 11, 0, 30), 30);

        assertNotNull("跨天实例不该被漏掉", next);
        assertEquals(LocalDateTime.of(2026, 11, 11, 8, 0), next.when);
    }
}
