package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * 三条通知的折叠卡片（v0.53.1）。
 *
 * <p>用户提了三个看着不相干的问题，根因只有两个：
 * <ul>
 *   <li><b>按钮长什么样</b>：响铃通知还在用系统模板，addAction 渲染出来就是灰色半透明药丸，
 *       和提前提醒那条的紫渐变放在一起，一眼不像同一款 App。</li>
 *   <li><b>按钮在不在</b>：睡前提醒的「明天请假」只挂 addAction，而折叠态（锁屏、刚弹出来）
 *       不渲染 action——设置里写着「可一键标记请假」，用户点不到。</li>
 * </ul>
 *
 * <p>所以这一组测试统一验「把 RemoteViews 真的应用出来之后，卡片上有什么」，
 * 而不是只看 XML 里写了什么——v0.52.3 的教训就是 XML 与运行时可能不一致。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class NotifCardTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private NotificationManager nm() {
        return (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        // 「仅在明天上班时提醒」默认是开的；关掉它，休息日 / 请假也能发通知，
        // 这样这组测试不必依赖「明天到底上不上班」
        Prefs.setBedtimeWorkdayOnly(ctx(), false);
    }

    private void enableDailyAlarm() {
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .apply();
    }

    /** 把卡片布局真正 inflate 出来，才验得到运行时塞进去的字与可见性。 */
    private View card(Notification n) {
        assertNotNull("必须挂自绘折叠布局", n.contentView);
        assertEquals("应统一使用 notif_content",
                R.layout.notif_content, n.contentView.getLayoutId());
        return n.contentView.apply(ctx(), new FrameLayout(ctx()));
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }

    // ================= 响铃通知 =================

    private AlarmService startRinging() {
        Intent i = new Intent(ctx(), AlarmService.class)
                .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                .putExtra(AlarmService.EXTRA_LABEL, "起床")
                .putExtra(AlarmService.EXTRA_DATE, LocalDate.now().toString())
                .putExtra(AlarmService.EXTRA_POPUP, false);
        return Robolectric.buildService(AlarmService.class, i).startCommand(0, 0).get();
    }

    /**
     * 响铃卡片上的两个按钮：停止（主）与 N 分钟后再响（次）。
     *
     * <p>这条同时钉住「不再是系统模板」：contentView 非空、且按钮文字来自我们的布局。
     */
    @Test
    public void ringingCard_showsStopAndSnooze() {
        enableDailyAlarm();
        Prefs.setSnoozeMinutes(ctx(), 5);
        AlarmService svc = startRinging();

        Notification n = shadowOf(svc).getLastForegroundNotification();
        assertNotNull("响铃必须发前台通知", n);
        View root = card(n);
        assertEquals("停止", text(root, R.id.nc_action));
        assertEquals(View.VISIBLE, root.findViewById(R.id.nc_action2).getVisibility());
        assertEquals("5 分钟后再响", text(root, R.id.nc_action2));
        // 响铃不需要倒计时：它正在响，不是「还剩多久」
        assertEquals(View.GONE, root.findViewById(R.id.nc_chrono).getVisibility());
    }

    // ================= 渠道选择（v0.54.2） =================
    //
    // 用户报「两个悬浮窗」：高优先级渠道在屏幕亮着时必然弹一张系统 heads-up，
    // 我们的横幅又叠上去。这两条钉住「什么时候该安静、什么时候该抢屏」。

    /**
     * 解锁 + 有悬浮窗权限：走低优先级渠道，不挂 FSI，也不挂系统 action。
     *
     * <p>界面已经由横幅负责，这条通知只需在栏里可点可停。渠道一高，heads-up 就会回来。
     */
    @Test
    public void floatPath_usesQuietChannelWithoutHeadsUp() {
        enableDailyAlarm();
        ShadowSettings.setCanDrawOverlays(true);
        try {
            AlarmService svc = startRinging();
            Notification n = shadowOf(svc).getLastForegroundNotification();

            assertEquals("能弹横幅就该用「已弹横幅」这条安静渠道",
                    App.CHANNEL_ALARM_BANNER, n.getChannelId());
            assertNull("有横幅就不该再挂全屏意图", n.fullScreenIntent);
            assertTrue("自绘卡片已有两个药丸，不该再挂系统 action",
                    n.actions == null || n.actions.length == 0);
        } finally {
            FloatingAlarm.hide();
        }
    }

    /** 锁屏 / 没给悬浮窗权限：高优先级渠道 + 全屏意图，这是唯一能叫醒人的路径。 */
    @Test
    public void noFloatPath_keepsHighChannelAndFullScreenIntent() {
        enableDailyAlarm();
        ShadowSettings.setCanDrawOverlays(false);

        AlarmService svc = startRinging();
        Notification n = shadowOf(svc).getLastForegroundNotification();

        assertEquals("没横幅时必须用高优先级渠道",
                App.CHANNEL_ALARM, n.getChannelId());
        assertNotNull("没有横幅时全屏意图是兜底", n.fullScreenIntent);
        assertNotNull("这条路径仍要挂系统 action", n.actions);
        assertEquals("停止", n.actions[0].title.toString());
    }

    /** 次数用尽时第二个按钮必须消失，否则点了只会弹「已用完」。 */
    @Test
    public void ringingCard_hidesSnoozeWhenExhausted() {
        enableDailyAlarm();
        Prefs.setSnoozeTimes(ctx(), 1);
        Prefs.incrementSnoozeCount(ctx(), 1, LocalDate.now());
        AlarmService svc = startRinging();

        View root = card(shadowOf(svc).getLastForegroundNotification());
        assertEquals("停止", text(root, R.id.nc_action));
        assertEquals("次数用尽就不该有这个按钮",
                View.GONE, root.findViewById(R.id.nc_action2).getVisibility());
    }

    /** 测试响铃只留「结束测试」——测试没有排班实例，小睡点了也不会响。 */
    @Test
    public void testRingingCard_hasOnlyStop() {
        enableDailyAlarm();
        Intent i = new Intent(ctx(), AlarmService.class)
                .setAction(AlarmService.ACTION_TEST)
                .putExtra(AlarmService.EXTRA_ALARM_ID, 0)
                .putExtra(AlarmService.EXTRA_LABEL, "起床");
        AlarmService svc = Robolectric.buildService(AlarmService.class, i).startCommand(0, 0).get();

        View root = card(shadowOf(svc).getLastForegroundNotification());
        assertEquals("结束测试", text(root, R.id.nc_action));
        assertEquals(View.GONE, root.findViewById(R.id.nc_action2).getVisibility());
    }

    // ================= 睡前提醒 =================

    private Notification bedtimeNotification() {
        new BedtimeReceiver().onReceive(ctx(), new Intent(BedtimeReceiver.ACTION_BEDTIME));
        return shadowOf(nm()).getNotification(BedtimeReminder.NOTIF_ID);
    }

    /**
     * 「明天请假」必须画在卡片上。
     *
     * <p>设置页那句「可一键标记请假」就是承诺这个按钮，而它原来只在展开后出现。
     * 这里把明天标成加班，让「明天是上班日」与运行测试的日期无关。
     */
    @Test
    public void bedtimeCard_hasSkipButtonOnWorkday() {
        enableDailyAlarm();
        Prefs.addOvertime(ctx(), LocalDate.now().plusDays(1));

        Notification n = bedtimeNotification();
        assertNotNull(n);
        View root = card(n);
        assertEquals("明天请假", text(root, R.id.nc_action));
        assertEquals(View.VISIBLE, root.findViewById(R.id.nc_action).getVisibility());
    }

    /** 明天已请假时不给按钮：那时候「请假」没有意义。 */
    @Test
    public void bedtimeCard_noSkipButtonWhenAlreadyOff() {
        enableDailyAlarm();
        Prefs.addVacation(ctx(), LocalDate.now().plusDays(1));

        Notification n = bedtimeNotification();
        assertNotNull(n);
        View root = card(n);
        assertEquals(View.GONE, root.findViewById(R.id.nc_action).getVisibility());
    }

    // ================= 稍后提醒期间的通知 =================

    /**
     * 点「5 分钟后再响」之后，通知栏必须留一条「稍后再响」。
     *
     * <p>原来服务一停、通知一撤，栏里就空了：用户看不到它到底还响不响、什么时候响。
     */
    @Test
    public void snooze_leavesNoticeWithCountdown() {
        enableDailyAlarm();
        Prefs.setSnoozeMinutes(ctx(), 5);
        Robolectric.buildService(AlarmService.class,
                new Intent(ctx(), AlarmService.class)
                        .setAction(AlarmService.ACTION_SNOOZE)
                        .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                        .putExtra(AlarmService.EXTRA_LABEL, "起床")
                        .putExtra(AlarmService.EXTRA_DATE, LocalDate.now().toString()))
                .startCommand(0, 0);

        Notification n = shadowOf(nm()).getNotification(SnoozeNotice.id(1));
        assertNotNull("小睡之后通知栏不能空着", n);
        View root = card(n);
        assertTrue("标题应说清是谁、要再响", text(root, R.id.nc_title).contains("稍后再响"));
        assertEquals("应有一个「取消」", "取消", text(root, R.id.nc_action2));
        assertEquals("要有倒计时", View.VISIBLE, root.findViewById(R.id.nc_chrono).getVisibility());

        long remaining = ((android.widget.Chronometer) root.findViewById(R.id.nc_chrono)).getBase()
                - SystemClock.elapsedRealtime();
        assertTrue("倒计时应在 5 分钟左右，实际 " + remaining / 1000 + " 秒",
                remaining > 3 * 60_000L && remaining < 6 * 60_000L);
    }

    /** 真的响起来时，那条预告要撤掉，否则栏里两条同时在。 */
    @Test
    public void snoozeNotice_cancelledWhenRinging() {
        enableDailyAlarm();
        SnoozeNotice.post(ctx(), 1, "起床", 5);
        assertNotNull("先确认已发出", shadowOf(nm()).getNotification(SnoozeNotice.id(1)));

        startRinging();
        assertNull("响铃后预告应撤掉", shadowOf(nm()).getNotification(SnoozeNotice.id(1)));
    }

    // ================= 折叠态尺寸（v0.53.2） =================
    //
    // 用户截图里「关闭闹钟」「取消」的字被上下各切掉一截。根因不是字号，是高度：
    // 折叠态有上限，内容超了不是整体缩小，而是**把最后一行压扁**——按钮那行首当其冲。
    // v0.53.1 的卡片是「标题 / 正文 / 倒计时」三行字 + 一行按钮，正好超了。

    private int dp(int v) {
        return Math.round(v * ctx().getResources().getDisplayMetrics().density);
    }

    /** 按钮高度写死 + 文字居中：即使真被压，字也在中间，不会被切成两半。 */
    @Test
    public void actionButtons_areFixedHeightAndCentered() {
        enableDailyAlarm();
        Prefs.setSnoozeMinutes(ctx(), 5);
        View root = card(shadowOf(startRinging()).getLastForegroundNotification());

        for (int id : new int[]{R.id.nc_action, R.id.nc_action2}) {
            TextView b = root.findViewById(id);
            assertEquals("按钮高度应写死 30dp（id=" + id + "）",
                    dp(30), b.getLayoutParams().height);
            assertEquals("按钮文字应居中（id=" + id + "）", Gravity.CENTER, b.getGravity());
        }
    }

    /**
     * 倒计时和正文必须同一行。
     *
     * <p>这就是省下来的那一行：三行文字 + 一行按钮 = 超限，两行文字 + 一行按钮 = 有余量。
     */
    @Test
    public void countdown_sitsOnSameRowAsText() {
        enableDailyAlarm();
        SnoozeNotice.post(ctx(), 1, "起床", 5);
        View root = card(shadowOf(nm()).getNotification(SnoozeNotice.id(1)));

        View text = root.findViewById(R.id.nc_text);
        View chrono = root.findViewById(R.id.nc_chrono);
        assertSame("倒计时应与正文同一行，而不是另起一行",
                text.getParent(), chrono.getParent());
        assertEquals("这一行应是横向的",
                LinearLayout.HORIZONTAL, ((LinearLayout) chrono.getParent()).getOrientation());
    }

    /**
     * 整张卡是**一行**：图标、文字、药丸并排（v0.54.0 回到 v0.53.0 的紧凑样子）。
     *
     * <p>v0.53.1 为了塞下倒计时改成「图标 + 三行文字」再加一行按钮，用户直接说
     * 「不如之前紧凑」，而且高度顶到折叠态上限、按钮被压扁。现在倒计时挤在正文右边，
     * 高度不变。
     */
    @Test
    public void card_isSingleRow() {
        enableDailyAlarm();
        Prefs.setSnoozeMinutes(ctx(), 5);
        View root = card(shadowOf(startRinging()).getLastForegroundNotification());

        LinearLayout row = (LinearLayout) root;
        assertEquals("卡片根布局应是横向的一行",
                LinearLayout.HORIZONTAL, row.getOrientation());
        // 图标 / 文字块 / 次按钮 / 主按钮，四个都在同一行
        assertEquals("图标、文字、两个药丸应并排在同一行", 4, row.getChildCount());
        assertSame("标题与正文应同属一个纵向文字块",
                root.findViewById(R.id.nc_title).getParent(),
                root.findViewById(R.id.nc_text).getParent().getParent());
    }
}
