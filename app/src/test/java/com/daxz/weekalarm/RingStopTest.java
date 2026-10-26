package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
 * 「闹钟响了却关不掉」的兜底（v0.58.0）。
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
        // v0.58.7：这一行现在只在「没有别的停止入口」时出现，所以先把通知关掉、
        // 并确认没有浮窗——这正是 v0.58.0 要救的那个场景
        notifications(false);
        FloatingAlarm.hide();
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

    /**
     * 通知开着时不该再出现首页那行（v0.58.7）。
     *
     * <p>用户真机截图：浮窗、通知栏、首页各有一个「停止」，看起来像三件不同的事。
     * 通知栏的按钮已经能停，这一行就是重复。
     */
    @Test
    public void ringingRow_hiddenWhenNotificationCanStop() throws Exception {
        resetPrefs();
        notifications(true);
        FloatingAlarm.hide();
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();

        setRinging(true);
        UiRefresh.request(a);
        ShadowLooper.idleMainLooper();

        assertEquals("通知栏已有停止按钮，首页不该再挂一行",
                View.GONE, a.findViewById(R.id.row_ringing).getVisibility());
        setRinging(false);
        ctl.pause().stop().destroy();
    }

    /**
     * 悬浮窗挂着时同样不该出现（v0.58.7）：横幅上那个方块就是停止键。
     *
     * <p>注意这里刻意把通知关掉——只靠浮窗也要算「有入口」，否则用户关掉通知后
     * 会在浮窗和首页看到两个停止按钮。
     */
    @Test
    public void ringingRow_hiddenWhenBannerCanStop() throws Exception {
        resetPrefs();
        notifications(false);
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();

        setRinging(true);
        assertTrue("前提：浮窗要真的挂上",
                FloatingAlarm.show(ctx(), 1, "起床", java.time.LocalDate.now().toString(), 5, true));
        UiRefresh.request(a);
        ShadowLooper.idleMainLooper();

        assertEquals("浮窗上已有停止键，首页不该再挂一行",
                View.GONE, a.findViewById(R.id.row_ringing).getVisibility());

        setRinging(false);
        FloatingAlarm.hide();
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
        // v0.73.5：通知降为「建议」（进 App 也能停），但后果仍要说清
        assertEquals("未开 · 响铃时得进 App 关", st.getText().toString());
        ctl.pause().stop().destroy();
    }

    // ---------- 4. 权限行的两处调整（真机结论，v0.58.1）----------

    /**
     * 真机验证：开「后台弹出界面」会连带开启全屏通知，反之不成立。
     * 所以引导里只留这一项，标题必须写清是哪一项。
     */
    @Test
    public void guide_popupRow_targetsBackgroundPopup() {
        resetPrefs();
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        View row = ctl.get().findViewById(R.id.perm_fsi);
        // 行内左侧竖排的第一个子 View 是标题
        android.view.ViewGroup inner = (android.view.ViewGroup)
                ((android.view.ViewGroup) row).getChildAt(0);
        assertEquals("后台弹出界面", ((TextView) inner.getChildAt(0)).getText().toString());
        ctl.pause().stop().destroy();
    }

    /**
     * 电池优化整行已删（v0.58.2）：实测自启动开着、电池优化受限时闹钟照响，
     * 这一项对本 App 没有作用。检查清单里留一个「你不需要管」的开关，
     * 只会让人以为还有事没做。
     *
     * <p>按项目规矩，删掉的布局 id 不能在测试里写 {@code R.id.xxx}（会直接编译失败），
     * 用 getIdentifier 断言它确实不存在。
     */
    @Test
    public void guide_batteryRow_isGone() {
        resetPrefs();
        int id = ctx().getResources().getIdentifier(
                "perm_battery", "id", ctx().getPackageName());
        assertEquals("这一行应该已经被删掉", 0, id);
        int stId = ctx().getResources().getIdentifier(
                "st_battery", "id", ctx().getPackageName());
        assertEquals("状态文案也一并删掉", 0, stId);
    }

    // ---------- 5. 兜底通知的消除路径（v0.74.3）----------

    /**
     * 前台服务被系统拒时发的兜底通知（{@link AlarmService#postFallbackNotification}）是
     * ongoing 的。它有两条消除路径，v0.74.2 只通了一条：
     * <ul>
     *   <li>点通知里的「停止」→ 经 {@code PendingIntent.getService} 把服务拉起来再
     *       {@code stopSelf}，{@code onDestroy} 顺带取消——通；</li>
     *   <li>点通知本体进响铃页、再按停止——原来不走任何取消路径，通知永远挂在栏里。</li>
     * </ul>
     * 这条测试钉第二种：从响铃页停止后，通知必须消失。
     */
    @Test
    public void fallbackNotification_cancelledWhenStoppingFromAlarmPage() {
        resetPrefs();
        notifications(true);
        // 模拟「前台服务起不来」：AlarmReceiver 的 catch 会发这条兜底通知
        AlarmService.postFallbackNotification(ctx(), 7, "起床", "2026-10-26", true);
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = shadowOf(nm);
        assertNotNull("兜底通知应先发出来", shadow.getNotification(null, AlarmService.NOTIF_ID));

        // 用户点通知本体进响铃页，滑动停止
        Intent i = new Intent(ctx(), AlarmActivity.class);
        i.putExtra(AlarmActivity.EXTRA_ALARM_ID, 7);
        i.putExtra(AlarmActivity.EXTRA_LABEL, "起床");
        i.putExtra(AlarmActivity.EXTRA_DATE, "2026-10-26");
        ActivityController<AlarmActivity> ctl =
                Robolectric.buildActivity(AlarmActivity.class, i).setup();
        ShadowLooper.idleMainLooper();
        ctl.get().findViewById(R.id.btn_stop).performClick();

        assertNull("从响铃页停止后，兜底通知必须取消",
                shadow.getNotification(null, AlarmService.NOTIF_ID));
        ctl.pause().stop().destroy();
    }

    /**
     * 小睡同样要能消掉兜底通知：用户点「再响 5 分钟」之后，栏里不该还挂着
     * 「时间到了 · 点此停止」——那条说的是现在这一响，已经处理了。
     */
    @Test
    public void fallbackNotification_cancelledWhenSnoozingFromAlarmPage() {
        resetPrefs();
        notifications(true);
        AlarmService.postFallbackNotification(ctx(), 8, "午休", "2026-10-26", true);
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = shadowOf(nm);
        assertNotNull("兜底通知应先发出来", shadow.getNotification(null, AlarmService.NOTIF_ID));

        Intent i = new Intent(ctx(), AlarmActivity.class);
        i.putExtra(AlarmActivity.EXTRA_ALARM_ID, 8);
        i.putExtra(AlarmActivity.EXTRA_LABEL, "午休");
        i.putExtra(AlarmActivity.EXTRA_DATE, "2026-10-26");
        ActivityController<AlarmActivity> ctl =
                Robolectric.buildActivity(AlarmActivity.class, i).setup();
        ShadowLooper.idleMainLooper();
        ctl.get().findViewById(R.id.btn_snooze).performClick();

        assertNull("小睡后，兜底通知必须取消",
                shadow.getNotification(null, AlarmService.NOTIF_ID));
        ctl.pause().stop().destroy();
    }
}
