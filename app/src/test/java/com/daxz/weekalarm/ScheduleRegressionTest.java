package com.daxz.weekalarm;

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
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v0.23.0 调度层回归：
 * ① requestCode 必须与「今天是哪天」无关——旧版用「相对今天的天数」编码，
 *    跨天后再取消，算出的 requestCode 与注册时不同，cancel 打不中，旧闹钟一直留在系统里；
 * ② 彻底停掉（删到没有启用的闹钟）时必须连「稍后提醒」一起取消；
 * ③ 提前通知与主闹钟的 requestCode 分段互不覆盖。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScheduleRegressionTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        Application app = (Application) ctx();
        app.getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setHorizonDays(ctx(), 90);
    }

    /** 跨天后，同一个日期的 requestCode 必须保持不变（否则取消会落空）。 */
    @Test
    public void requestCode_stableAcrossDays() {
        LocalDate target = LocalDate.now().plusDays(10);
        int before = ScheduleManager.preNoticeRequestCode(3, target);
        assertEquals("同一日期应算出同一个 requestCode",
                before, ScheduleManager.preNoticeRequestCode(3, target));

        // 把系统时钟往前推 5 天：旧实现（相对今天的偏移）此时会得到不同的值
        ShadowSystemClock.advanceBy(Duration.ofDays(5));
        assertEquals("跨天后 requestCode 不应漂移",
                before, ScheduleManager.preNoticeRequestCode(3, target));
    }

    /** 注册后跨天，cancelAll 仍应把该实例取消掉。 */
    @Test
    public void cancelAll_afterDayRollsOver() {
        LocalDate d = LocalDate.now().plusDays(1);
        Prefs.setAlarms(ctx(), java.util.Arrays.asList(
                new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true)));
        ScheduleManager.rescheduleAll(ctx());
        assertTrue("刚注册应能查到", ScheduleManager.isAlarmRegistered(ctx(), 1, d));

        // 模拟隔天：把「今天」推后一天，再走一次取消
        ShadowSystemClock.advanceBy(Duration.ofDays(1));
        ScheduleManager.cancelAll(ctx());
        assertFalse("跨天后 cancelAll 必须仍能取消已注册的实例",
                ScheduleManager.isAlarmRegistered(ctx(), 1, d));
    }

    /**
     * 「稍后提醒」会登记在哪天：现在 + 间隔后的那一天。
     *
     * <p>不能写死 {@link LocalDate#now()}——晚上 23:55 之后跑测试，加 5 分钟就跨到次日，
     * 于是「注册了却用今天去查」恒为假，测试在一天里只有 23:55 之后这几分钟会红。
     * 这种只在某个钟点炸的测试最阴，排查时根本想不到和时间有关。
     */
    private LocalDate snoozeDay() {
        return LocalDateTime.now()
                .plusMinutes(Math.max(1, Prefs.getSnoozeMinutes(ctx()))).toLocalDate();
    }

    /** 彻底停掉要连「稍后提醒」一起清，否则点了小睡再删掉闹钟，几分钟后照样响。 */
    @Test
    public void cancelAllIncludingTransient_clearsSnooze() {
        LocalDate day = snoozeDay();
        ScheduleManager.snooze(ctx(), 1, "起床");
        assertTrue(ScheduleManager.isSnoozeRegistered(ctx(), 1, day));

        ScheduleManager.cancelAllIncludingTransient(ctx());
        assertFalse("关开关后不应再有待触发的小睡",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, day));
    }

    /** cancelAll 不能误伤小睡（响铃后重排时小睡要存活）。 */
    @Test
    public void cancelAll_keepsSnooze() {
        LocalDate day = snoozeDay();
        ScheduleManager.snooze(ctx(), 1, "起床");
        ScheduleManager.cancelAll(ctx());
        assertTrue("普通重排不应取消待触发的小睡",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, day));
    }

}
