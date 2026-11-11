package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排失败的自愈守卫（{@link RescheduleGuard}）。
 *
 * <p><b>为什么补这组测试</b>：{@code rescheduleAll} 是「先 cancelAll 再重设」，
 * 中途抛异常就意味着系统里可能一个闹钟都不剩。三处调用点原来全是
 * {@code catch (Throwable ignore)}——失败不重试、不留痕、不记日志，
 * 用户那天早上只会发现「闹钟没响」，App 自己毫不知情。
 *
 * <p>这种缺口真机测不出来：正常路径永远是成功的，要复现必须让重排抛异常，
 * 所以用可注入的 {@link RescheduleGuard.Work} 来造这个失败。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RescheduleGuardTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        Application app = (Application) ctx();
        SharedPreferences sp = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setHorizonDays(ctx(), 30);
        Prefs.setPreNoticeMinutes(ctx(), 0);
    }

    /** 造一个必然失败的活：模拟 rescheduleAll 在中途抛异常。 */
    private static final RescheduleGuard.Work BOOM = app -> {
        throw new IllegalStateException("模拟重排中途失败");
    };

    private ShadowAlarmManager alarmShadow() {
        return shadowOf((AlarmManager) ctx().getSystemService(Context.ALARM_SERVICE));
    }

    /** 找出守卫挂的那条重试（按 action 认，不按 index——别的东西也可能在排闹钟）。 */
    private ShadowAlarmManager.ScheduledAlarm findRetry() {
        for (ShadowAlarmManager.ScheduledAlarm sa : alarmShadow().getScheduledAlarms()) {
            PendingIntent pi = sa.operation;
            if (pi == null) continue;
            Intent i = shadowOf(pi).getSavedIntent();
            if (i != null && RescheduleGuard.ACTION_RETRY.equals(i.getAction())) return sa;
        }
        return null;
    }

    /** 失败必须留痕：否则「不响了」这件事只存在于用户的抱怨里。 */
    @Test
    public void failedReschedule_leavesPendingMark() {
        boolean ok = RescheduleGuard.run(ctx(), BOOM, true);
        assertFalse("失败要如实返回 false", ok);
        assertTrue("失败必须留下欠账标记", Prefs.isReschedulePending(ctx()));
    }

    /** 成功要把旧欠账清掉，否则每次进首页都白排一遍。 */
    @Test
    public void successfulReschedule_clearsPendingMark() {
        Prefs.setReschedulePending(ctx(), true);
        boolean ok = RescheduleGuard.run(ctx(), app -> { }, true);
        assertTrue(ok);
        assertFalse("排上了就不该再欠着", Prefs.isReschedulePending(ctx()));
    }

    /** 失败后要挂一次 60 秒后的重试——这是设备不重启时最近的一次自愈机会。 */
    @Test
    public void failure_armsOneRetryAfterAMinute() {
        // 用 ELAPSED_REALTIME 挂的，所以拿开机计时比：改系统时间不该影响「60 秒后」
        long before = SystemClock.elapsedRealtime();
        RescheduleGuard.run(ctx(), BOOM, true);

        ShadowAlarmManager.ScheduledAlarm retry = findRetry();
        assertNotNull("失败后必须挂上重试", retry);

        long delay = retry.triggerAtTime - before;
        assertTrue("重试不能提前（实际 " + delay + "ms）", delay >= 59_000L);
        assertTrue("重试不能拖太久（实际 " + delay + "ms）", delay <= 61_000L);
        // 用 ELAPSED_REALTIME 而不是 RTC：改系统时间不该影响「60 秒后」这件事
        assertEquals(AlarmManager.ELAPSED_REALTIME, retry.type);
    }

    /**
     * 重试再失败就只留标记，不再续挂。
     *
     * <p>不这么剪的话，一个必然失败的排程会变成每 60 秒醒一次的后台闹钟——
     * 比原来的静默更糟。
     */
    @Test
    public void retryRun_doesNotArmAnotherRetry() {
        RescheduleGuard.run(ctx(), BOOM, false);
        assertTrue("重试失败同样要留标记", Prefs.isReschedulePending(ctx()));
        assertNull("不许链式续挂", findRetry());
    }

    /** 没有欠账时进首页不该白跑一遍全量重排（那是上百次 IPC）。 */
    @Test
    public void runIfPending_doesNothingWithoutMark() {
        assertFalse(RescheduleGuard.runIfPending(ctx()));
        assertTrue("前提：本来就没有闹钟", Prefs.getScheduledDates(ctx()).isEmpty());
    }

    /** 有欠账时进首页要真排上，并把标记清掉。 */
    @Test
    public void runIfPending_reschedulesAndClearsMark() {
        SharedPreferences sp = ((Application) ctx())
                .getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        List<Alarm> alarms = new ArrayList<>();
        alarms.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(alarms))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .commit();

        ScheduleManager.cancelAll(ctx());
        Prefs.setReschedulePending(ctx(), true);

        assertTrue("有欠账就得动手", RescheduleGuard.runIfPending(ctx()));
        assertFalse("排上了就清标记", Prefs.isReschedulePending(ctx()));
        assertFalse("闹钟要真的排回系统里", Prefs.getScheduledDates(ctx()).isEmpty());
    }
}
