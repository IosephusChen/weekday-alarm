package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNotificationManager;

import java.lang.reflect.Field;

/**
 * 「闹钟响了却关不掉」的兜底（v1.47.1）。
 *
 * <p>起因是用户真机测试：只开自启动、关掉通知，划掉 App 后闹钟照响，但整屏响铃页
 * 被系统拦住（后台拉起 Activity 受限）、通知栏没有按钮，回到 App 也没有任何入口，
 * 只能强行停止 App。
 *
 * <p>这里钉两条独立的保命措施：
 * <ol>
 *   <li>主界面的「正在响铃 · 停止」入口——不依赖任何权限；</li>
 *   <li>通知不可用时，响铃强制封顶 5 分钟——没有停止入口时必须自己停。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingStopTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private void setRinging(boolean ringing) throws Exception {
        Field f = AlarmService.class.getDeclaredField("ringing");
        f.setAccessible(true);
        f.setBoolean(null, ringing);
    }

    // ---------- 1. 主界面的停止入口 ----------

    @Test
    public void ringingRow_isHiddenWhenNotRinging() throws Exception {
        resetPrefs();
        setRinging(false);
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        View row = ctl.get().findViewById(R.id.row_ringing);
        assertEquals("平时不该占位置", View.GONE, row.getVisibility());
        ctl.pause().stop().destroy();
    }

    /**
     * 响铃时出现，点「停止」立刻收起并发出停止指令。
     *
     * <p>这条入口的价值在于：它不需要通知权限、不需要悬浮窗、也不需要能被系统拉起来——
     * 只要用户能打开 App，就一定能停。
     */
    @Test
    public void ringingRow_appearsWhileRingingAndStopWorks() throws Exception {
        resetPrefs();
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        View row = a.findViewById(R.id.row_ringing);
        assertEquals(View.GONE, row.getVisibility());

        // 模拟响铃中（服务已把标志置位），然后用一次刷新信号触发重绘
        setRinging(true);
        UiRefresh.request(a);
        ShadowLooper.idleMainLooper();
        assertEquals("响铃中应出现停止入口", View.VISIBLE, row.getVisibility());

        a.findViewById(R.id.btn_stop_ringing).performClick();
        assertEquals("点完应立刻收起", View.GONE, row.getVisibility());

        Intent started = shadowOf((Application) ctx()).getNextStartedService();
        assertNotNull("应把停止指令发给响铃服务", started);
        assertEquals(AlarmService.ACTION_STOP, started.getAction());

        setRinging(false);
        ctl.pause().stop().destroy();
    }

    // ---------- 2. 通知不可用时的刹车 ----------

    private void notifications(boolean enabled) {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = shadowOf(nm);
        shadow.setNotificationsEnabled(enabled);
    }

    @Test
    public void ringDuration_uncappedWhenNotificationsWork() {
        resetPrefs();
        notifications(true);
        Prefs.setRingMinutes(ctx(), 0);
        assertEquals("通知正常时，「不限」就该是不限（0）",
                0, AlarmService.effectiveRingMinutes(ctx()));
        Prefs.setRingMinutes(ctx(), 10);
        assertEquals(10, AlarmService.effectiveRingMinutes(ctx()));
    }

    @Test
    public void ringDuration_cappedWhenNotificationsOff() {
        resetPrefs();
        notifications(false);
        Prefs.setRingMinutes(ctx(), 0);
        assertEquals("没有停止入口时不能无限响",
                AlarmService.DEGRADED_MAX_MINUTES, AlarmService.effectiveRingMinutes(ctx()));
        Prefs.setRingMinutes(ctx(), 10);
        assertEquals("用户设得比上限长，也要封顶",
                AlarmService.DEGRADED_MAX_MINUTES, AlarmService.effectiveRingMinutes(ctx()));
        Prefs.setRingMinutes(ctx(), 2);
        assertEquals("用户设得更短就按用户的来",
                2, AlarmService.effectiveRingMinutes(ctx()));
    }

    // ---------- 3. 引导页的两处改动 ----------

    @Test
    public void guide_hasAutostartRow() {
        resetPrefs();
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        View row = ctl.get().findViewById(R.id.perm_autostart);
        assertNotNull("第 3 页必须有自启动一项", row);
        assertEquals("这一项是要用户点的", View.VISIBLE, row.getVisibility());
        ctl.pause().stop().destroy();
    }

    /** 通知关掉时，状态行要说清后果，而不是一句温吞的「去开启」。 */
    @Test
    public void guide_notificationOff_saysAlarmCannotBeStopped() {
        resetPrefs();
        notifications(false);
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        TextView st = ctl.get().findViewById(R.id.st_notif);
        assertEquals("未开 · 闹钟响了关不掉", st.getText().toString());
        ctl.pause().stop().destroy();
    }
}
