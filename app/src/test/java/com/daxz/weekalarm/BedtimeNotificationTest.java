package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 睡前提醒「说过的话要跟着改」+ 界面外的状态变更要立刻反映到界面上（v1.46.7）。
 *
 * <p>这两条都是试用第 4 点暴露的：用户在 App 里加了闹钟，通知栏那条仍写着
 * 「明天没有会响的闹钟」；在通知栏点了「明天请假」，App 界面要退到桌面再进来才更新。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BedtimeNotificationTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        // 通知开着才谈得上「刷新通知」
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
    }

    private BedtimeReminder.Model model(boolean notify, String title, String text) {
        return new BedtimeReminder.Model(LocalDate.now().plusDays(1), false, true,
                notify, "起床", 7, 30, notify, title, text);
    }

    /** 当前挂在通知栏上的那条睡前提醒（没有则 null）。 */
    private Notification shown() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = org.robolectric.Shadows.shadowOf(nm);
        return shadow.getNotification(BedtimeReminder.NOTIF_ID);
    }

    private String shownText() {
        Notification n = shown();
        return n == null ? null : n.extras.getString(Notification.EXTRA_TEXT);
    }

    private static boolean containsText(View root, String needle) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(needle)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), needle)) return true;
            }
        }
        return false;
    }

    // ---------- 通知内容刷新 ----------

    /**
     * 没发过通知时不该凭空冒出一条——「刷新」的语义是改现有的，不是补发。
     */
    @Test
    public void refresh_whenNothingShown_doesNotPostAnything() {
        assertNull(shown());
        BedtimeReminder.refreshIfShown(ctx(),
                model(true, "明天要上班", "明早 07:30 起床 会响，早点睡"));
        assertNull("没发过就不该冒出来", shown());
    }

    /**
     * 核心用例：通知栏那条写着「明天没有会响的闹钟」，用户加了闹钟后应改口。
     */
    @Test
    public void refresh_rewritesTextInPlace() {
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明天没有会响的闹钟，记得加一个"));
        assertEquals("明天没有会响的闹钟，记得加一个", shownText());

        BedtimeReminder.refreshIfShown(ctx(),
                model(true, "明天要上班", "明早 07:30 起床 会响，早点睡"));

        assertEquals("明早 07:30 起床 会响，早点睡", shownText());
    }

    /** 状态变成「不该提醒」时（如只在上班日提醒、明天休息），应当撤掉而不是留着旧文案。 */
    @Test
    public void refresh_cancelsWhenShouldNotNotify() {
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明天没有会响的闹钟，记得加一个"));
        assertNotNull(shown());

        BedtimeReminder.refreshIfShown(ctx(), model(false, "明天休息", "明天不用早起，放心睡"));

        assertNull("不该提醒时就撤掉，别留旧话", shown());
    }

    // ---------- 界面外的变更要立刻反映到界面 ----------

    /** 通知栏「明天请假」改完后要发刷新信号，否则界面得等下一次 onResume。 */
    @Test
    public void skipTomorrow_emitsUiRefresh() {
        final AtomicInteger hits = new AtomicInteger();
        BroadcastReceiver spy = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                hits.incrementAndGet();
            }
        };
        ContextCompat.registerReceiver(ctx(), spy,
                new IntentFilter(UiRefresh.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

        new BedtimeReceiver().onReceive(ctx(),
                new Intent(ctx(), BedtimeReceiver.class)
                        .setAction(BedtimeReceiver.ACTION_SKIP_TOMORROW));
        ShadowLooper.idleMainLooper();

        assertTrue("明天应被标为请假", Prefs.isVacation(ctx(), LocalDate.now().plusDays(1)));
        assertEquals("应发出一次界面刷新信号", 1, hits.get());
        ctx().unregisterReceiver(spy);
    }

    /**
     * 端到端：界面开着时，外部（通知栏 / 小组件）改了闹钟列表，广播一到就要重画，
     * 不用用户退到桌面再进来。
     */
    @Test
    public void uiRefresh_repaintsOpenActivity() {
        // 全新安装会预置「07:30 起床 / 12:30 午休」，先清空，免得断言撞上预置值
        Prefs.setAlarms(ctx(), new ArrayList<>());
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        LinearLayout list = a.findViewById(R.id.alarm_list);
        assertFalse("开始时不该有 06:15 这条", containsText(list, "06:15"));

        // 模拟界面之外的改动：数据先变，界面还不知道
        List<Alarm> alarms = new ArrayList<>();
        alarms.add(new Alarm(1, 6, 15, "赶早班车", Alarm.Mode.DAILY, true));
        Prefs.setAlarms(ctx(), alarms);
        assertFalse("没有刷新信号时界面应保持旧样子", containsText(list, "06:15"));

        UiRefresh.request(a);
        ShadowLooper.idleMainLooper();

        assertTrue("收到刷新信号后应立刻画出新闹钟", containsText(list, "06:15"));
        ctl.pause().stop().destroy();
    }
}
