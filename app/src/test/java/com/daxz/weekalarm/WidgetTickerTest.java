package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 小组件的跨日刷新（v1.71.4）。
 *
 * <p>用户 00:07 截图：已经是周一，4×2 组件还显示「今天休息 / 周日」。
 * 根因是组件只在系统周期更新（最短 30 分钟）或 App 主动刷新时才重画，
 * 零点那一刻没人叫它。修法是每天 00:00:30 挂一条闹钟。
 *
 * <p>这里钉两件事：跨日时刻算得对（含「已过零点」与「正好在零点」两个边界），
 * 以及 {@link Widgets#updateAll} 真的把这条闹钟挂上了——后者是接线，
 * 光算得对但没人调用等于没修。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WidgetTickerTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static long at(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi, s), SH)
                .toInstant().toEpochMilli();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    /** 零点前：今天的 00:00:30 还没到，就挂今天。 */
    @Test
    public void beforeMidnight_ticksAtTodayMidnight() {
        assertEquals("10-25 23:00 应挂到 10-26 00:00:30",
                at(2026, 10, 26, 0, 0, 30), WidgetTicker.nextTickMillis(at(2026, 10, 25, 23, 0, 0), SH));
    }

    /** 零点后（用户截图那一刻）：今天的已经过了，挂明天。 */
    @Test
    public void afterMidnight_ticksAtTomorrowMidnight() {
        assertEquals("10-26 00:07 应挂到 10-27 00:00:30",
                at(2026, 10, 27, 0, 0, 30), WidgetTicker.nextTickMillis(at(2026, 10, 26, 0, 7, 0), SH));
    }

    /** 正好 00:00:30：按「已到点」处理，挂明天，避免同一条反复触发。 */
    @Test
    public void exactlyAtTickTime_movesToTomorrow() {
        assertEquals(at(2026, 10, 27, 0, 0, 30),
                WidgetTicker.nextTickMillis(at(2026, 10, 26, 0, 0, 30), SH));
    }

    /** 每月最后一天也要跨得过去（10-31 → 11-01），别写死 30 天。 */
    @Test
    public void monthBoundary_rollsOver() {
        assertEquals("10-31 23:59 应挂到 11-01 00:00:30",
                at(2026, 11, 1, 0, 0, 30), WidgetTicker.nextTickMillis(at(2026, 10, 31, 23, 59, 0), SH));
    }

    /** 接线：每次刷新组件都要顺手把下一天的闹钟挂上。 */
    @Test
    public void updateAll_armsTheMidnightTick() {
        AlarmManager am = (AlarmManager) ctx().getSystemService(Context.ALARM_SERVICE);
        assertNotNull(am);
        Widgets.updateAll(ctx());

        ShadowAlarmManager shadow = shadowOf(am);
        ShadowAlarmManager.ScheduledAlarm next = shadow.getNextScheduledAlarm();
        assertNotNull("刷新组件后应挂着一条跨日闹钟", next);

        PendingIntent op = next.operation;
        assertNotNull(op);
        String target = shadowOf(op).getSavedIntent().getComponent() == null
                ? "" : shadowOf(op).getSavedIntent().getComponent().getClassName();
        assertTrue("闹钟应指向 WidgetTickReceiver，实际：" + target,
                target.endsWith("WidgetTickReceiver"));

        ZonedDateTime at = java.time.Instant.ofEpochMilli(next.triggerAtTime)
                .atZone(ZoneId.systemDefault());
        assertEquals("应在 00:00:30 触发", 0, at.getHour());
        assertEquals(0, at.getMinute());
        assertEquals(30, at.getSecond());
        assertTrue("必须晚于此刻", next.triggerAtTime > System.currentTimeMillis());
    }
}
