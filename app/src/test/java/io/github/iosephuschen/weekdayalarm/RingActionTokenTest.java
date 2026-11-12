package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * M-1 守门：响铃通知上的「停止 / N 分钟后再响」只能被「这一轮响铃自己发出的」按钮触发。
 *
 * <p>持有「通知使用权」的应用能读到 {@link Notification.Action} 里的 PendingIntent
 * 并直接 {@code send()}，用户没碰手机也能静默停铃 / 静默续睡。{@code FLAG_IMMUTABLE}
 * 只挡「替换内容」，挡不住「代为调用」。
 *
 * <p>这几条先在旧代码上写，必须红——红说明它真的抓得到漏洞；绿了就是断言写松了。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingActionTokenTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        Prefs.setBedtimeWorkdayOnly(ctx(), false);
        // 没有悬浮窗权限 → 走系统 action 那条路，按钮才挂在 Notification.actions 上
        ShadowSettings.setCanDrawOverlays(false);
    }

    private void enableDailyAlarm() {
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .apply();
    }

    private AlarmService startRinging() {
        Intent i = new Intent(ctx(), AlarmService.class)
                .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                .putExtra(AlarmService.EXTRA_LABEL, "起床")
                .putExtra(AlarmService.EXTRA_DATE, LocalDate.now().toString())
                .putExtra(AlarmService.EXTRA_POPUP, false);
        return Robolectric.buildService(AlarmService.class, i).startCommand(0, 0).get();
    }

    /**
     * 旧代码这一条必须红：不带任何令牌的 ACTION_STOP 会把服务停掉。
     *
     * <p>这正是攻击形态——别的应用拿着通知里的 PendingIntent 直接发，
     * 服务端只认 action，不认来源。
     */
    @Test
    public void forgedStop_withoutToken_keepsRinging() {
        enableDailyAlarm();
        AlarmService svc = startRinging();

        assertFalse("还没发停止指令，不该已经停了", shadowOf(svc).isStoppedBySelf());

        svc.onStartCommand(new Intent(ctx(), AlarmService.class)
                .setAction(AlarmService.ACTION_STOP), 0, 0);

        assertFalse("没有令牌的 ACTION_STOP 必须被拒，铃要继续响",
                shadowOf(svc).isStoppedBySelf());
    }

    /** 同上，小睡分支：旧代码会记账并停铃，等于替用户睡过去了。 */
    @Test
    public void forgedSnooze_withoutToken_doesNotSnooze() {
        enableDailyAlarm();
        Prefs.setSnoozeTimes(ctx(), 3);
        AlarmService svc = startRinging();

        LocalDate today = LocalDate.now();
        int before = Prefs.getSnoozeCount(ctx(), 1, today);

        svc.onStartCommand(new Intent(ctx(), AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE)
                .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                .putExtra(AlarmService.EXTRA_LABEL, "起床")
                .putExtra(AlarmService.EXTRA_DATE, today.toString()), 0, 0);

        assertEquals("伪造的小睡不能记账", before, Prefs.getSnoozeCount(ctx(), 1, today));
        assertFalse("伪造的小睡不能停铃", shadowOf(svc).isStoppedBySelf());
    }

    /** 通知自带的按钮必须照常工作——修复不能把正常路径一起封死。 */
    @Test
    public void notificationStop_stillStops() {
        enableDailyAlarm();
        AlarmService svc = startRinging();

        svc.onStartCommand(stopAction(svc), 0, 0);

        assertTrue("通知自带的「停止」必须仍然有效",
                shadowOf(svc).isStoppedBySelf());
    }

    /**
     * 令牌必须每轮新：上一轮响铃留下的「停止」PendingIntent 不能停下一轮。
     * 这条同时钉住 {@code FLAG_UPDATE_CURRENT}——少了它 extras 会冻在第一次响铃。
     */
    @Test
    public void oldToken_cannotStopNextRing() {
        enableDailyAlarm();

        AlarmService first = startRinging();
        Intent firstStop = stopAction(first);
        String firstToken = firstStop.getStringExtra(AlarmService.EXTRA_RING_TOKEN);
        assertNotNull("停止按钮必须带令牌", firstToken);
        first.onStartCommand(firstStop, 0, 0);
        assertTrue(shadowOf(first).isStoppedBySelf());

        AlarmService second = startRinging();
        String secondToken =
                stopAction(second).getStringExtra(AlarmService.EXTRA_RING_TOKEN);
        assertNotNull(secondToken);
        assertNotEquals("每轮响铃都必须换新令牌", firstToken, secondToken);

        second.onStartCommand(new Intent(ctx(), AlarmService.class)
                .setAction(AlarmService.ACTION_STOP)
                .putExtra(AlarmService.EXTRA_RING_TOKEN, firstToken), 0, 0);

        assertFalse("上一轮的令牌不能停这一轮", shadowOf(second).isStoppedBySelf());
    }

    private Intent stopAction(AlarmService svc) {
        Notification n = shadowOf(svc).getLastForegroundNotification();
        assertNotNull("响铃必须有前台通知", n);
        assertNotNull("没有横幅时通知要挂系统按钮", n.actions);
        return shadowOf(n.actions[0].actionIntent).getSavedIntent();
    }
}
