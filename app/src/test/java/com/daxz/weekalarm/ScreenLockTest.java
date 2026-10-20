package com.daxz.weekalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 「解锁只发通知、锁屏才弹窗」（v1.23.1）。
 *
 * <p>这条规则只有一个判断点（{@link ScreenLock#shouldPopup}），但有三条触发路径
 * （正式响铃 / 提前提醒 / 测试），任何一条漏判都会退回「通知 + 整屏弹窗同时出现」。
 * 这里既测判断本身，也测两条广播路径真的没有去拉 Activity。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ScreenLockTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void reset() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setEnabled(ctx(), true);
    }

    private void setScreen(boolean interactive, boolean locked) {
        PowerManager pm = (PowerManager) ctx().getSystemService(Context.POWER_SERVICE);
        shadowOf(pm).setIsInteractive(interactive);
        KeyguardManager km = (KeyguardManager) ctx().getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(km).setKeyguardLocked(locked);
    }

    @Test
    public void unlockedAndAwake_onlyNotification() {
        setScreen(true, false);
        assertFalse("解锁使用中不该弹窗", ScreenLock.shouldPopup(ctx()));
    }

    @Test
    public void lockedScreen_popsUp() {
        setScreen(true, true);
        assertTrue("锁屏（亮屏未解锁）应弹窗", ScreenLock.shouldPopup(ctx()));
    }

    /** 没有锁屏密码的设备息屏时 keyguard 不算 locked，必须靠「屏幕不交互」这一条兜住。 */
    @Test
    public void screenOff_popsUpEvenWithoutKeyguard() {
        setScreen(false, false);
        assertTrue("息屏应弹窗，否则闹钟只响一条看不见的通知", ScreenLock.shouldPopup(ctx()));
    }

    /** 正式响铃：解锁时只起前台服务（发通知），不拉 AlarmActivity。 */
    @Test
    public void alarmReceiver_unlocked_doesNotOpenAlarmPage() {
        reset();
        setScreen(true, false);
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        new AlarmReceiver().onReceive(ctx(), alarmIntent());
        assertNull("解锁时不该直接拉起响铃页",
                shadowOf(app).getNextStartedActivity());
        assertNotNull("响铃服务必须照常启动（声音 + 通知）",
                shadowOf(app).getNextStartedService());
    }

    /** 锁屏时反过来：必须把响铃页推到最前面。 */
    @Test
    public void alarmReceiver_locked_opensAlarmPage() {
        reset();
        setScreen(true, true);
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        new AlarmReceiver().onReceive(ctx(), alarmIntent());
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull("锁屏时应直接拉起响铃页", started);
        assertTrue("拉起的应是 AlarmActivity，实际：" + started.getComponent(),
                started.getComponent() != null
                        && started.getComponent().getClassName().endsWith("AlarmActivity"));
    }

    /** 提前提醒：解锁时不弹窗，但通知照发。 */
    @Test
    public void preNotice_unlocked_doesNotOpenPopup() {
        reset();
        setScreen(true, false);
        LocalDate d = LocalDate.now();
        ScheduleManager.setAlarm(ctx(), LocalDateTime.now().plusHours(1), 1, "起床");
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        new PreNoticeReceiver().onReceive(ctx(), preNoticeIntent(d));
        assertNull("解锁时不该弹整屏提醒", shadowOf(app).getNextStartedActivity());
    }

    /** 提前提醒：锁屏时才弹。 */
    @Test
    public void preNotice_locked_opensPopup() {
        reset();
        setScreen(true, true);
        LocalDate d = LocalDate.now();
        ScheduleManager.setAlarm(ctx(), LocalDateTime.now().plusHours(1), 1, "起床");
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        new PreNoticeReceiver().onReceive(ctx(), preNoticeIntent(d));
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull("锁屏时应弹出提前提醒", started);
        assertTrue("拉起的应是 PreNoticeActivity，实际：" + started.getComponent(),
                started.getComponent() != null
                        && started.getComponent().getClassName().endsWith("PreNoticeActivity"));
    }

    private Intent alarmIntent() {
        Intent i = new Intent(ctx(), AlarmReceiver.class)
                .setAction("com.daxz.weekalarm.ALARM")
                .setData(android.net.Uri.parse("daxz://alarm/1/" + LocalDate.now()));
        i.putExtra(AlarmReceiver.EXTRA_ALARM_ID, 1);
        i.putExtra(AlarmReceiver.EXTRA_LABEL, "起床");
        return i;
    }

    private Intent preNoticeIntent(LocalDate d) {
        Intent i = new Intent(ctx(), PreNoticeReceiver.class)
                .setAction(PreNoticeReceiver.ACTION_PRE_NOTICE)
                .setData(android.net.Uri.parse("daxz://prenotice/1/" + d));
        i.putExtra(PreNoticeReceiver.EXTRA_ALARM_ID, 1);
        i.putExtra(PreNoticeReceiver.EXTRA_LABEL, "起床");
        i.putExtra(PreNoticeReceiver.EXTRA_DATE, d.toString());
        i.putExtra(PreNoticeReceiver.EXTRA_MINUTES, 30);
        i.putExtra(PreNoticeReceiver.EXTRA_TIME_TEXT, "07:00");
        return i;
    }
}
