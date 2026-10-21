package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 提前提醒（v1.23）：1 小时档位 + 主动弹窗。
 *
 * <p>这一版把提前提醒从「只躺在通知栏」改成「弹窗」，涉及三处容易各改各的：
 * 设置页的档位映射、通知里的文案、弹窗页的标题。这里把它们钉在一起测。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PreNoticeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void reset() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    /** 60 分钟要写「1 小时」，不能出现「60 分钟后」这种机器话。 */
    @Test
    public void minutesText_readsLikeHuman() {
        assertEquals("5 分钟", PreNoticeReceiver.minutesText(5));
        assertEquals("30 分钟", PreNoticeReceiver.minutesText(30));
        assertEquals("1 小时", PreNoticeReceiver.minutesText(60));
        assertEquals("2 小时", PreNoticeReceiver.minutesText(120));
    }

    /** 设置页的提前提醒对话框要有「提前 1 小时」，且存进去的是 60。 */
    @Test
    public void preNoticeDialog_hasOneHourOption() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 0);
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        // v1.27：整行改成了开关（点了就是开/关），调提前量要点副标题那一行
        ctl.get().findViewById(R.id.tv_pre_notice).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出提前提醒设置", d);
        RadioButton hour = d.findViewById(R.id.rb_pre_60);
        assertNotNull("缺少「提前 1 小时」档位", hour);
        assertEquals("提前 1 小时", hour.getText().toString());

        RadioGroup rg = d.findViewById(R.id.rg_pre_notice);
        rg.check(R.id.rb_pre_60);
        ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // 对话框按钮的点击经 Handler 派发，要让主线程跑一轮才会执行回调
        ShadowLooper.idleMainLooper();
        assertEquals("选「提前 1 小时」应存成 60 分钟", 60, Prefs.getPreNoticeMinutes(ctx()));
        ctl.pause().stop().destroy();
    }

    /** 已经设成 60 时，再打开对话框要选中「提前 1 小时」，而不是退回 30。 */
    @Test
    public void preNoticeDialog_restoresOneHourSelection() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        // v1.27：整行改成了开关（点了就是开/关），调提前量要点副标题那一行
        ctl.get().findViewById(R.id.tv_pre_notice).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        RadioGroup rg = d.findViewById(R.id.rg_pre_notice);
        assertEquals(R.id.rb_pre_60, rg.getCheckedRadioButtonId());
        ctl.pause().stop().destroy();
    }

    /**
     * v1.27：整行改成开关。关掉写 0，再打开要回到上次选的 15 分钟。
     *
     * <p>这条防的是「关一下就被悄悄改成 1 小时」——那是最容易被忽略、
     * 又最容易被用户当成 bug 的行为：明明显式关过一次，回来却变了数。
     */
    @Test
    public void switchOff_thenOn_restoresPreviousMinutes() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 15);
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity a = ctl.get();

        a.findViewById(R.id.row_pre_notice).performClick();
        assertEquals("关掉应写 0", 0, Prefs.getPreNoticeMinutes(ctx()));
        assertEquals("关着时副标题该说明「开着会怎样」，而不是复述「关闭」",
                "响铃前先发一条通知",
                ((TextView) a.findViewById(R.id.tv_pre_notice)).getText().toString());

        a.findViewById(R.id.row_pre_notice).performClick();
        assertEquals("再打开要回到上次选的 15 分钟，不是默认 1 小时",
                15, Prefs.getPreNoticeMinutes(ctx()));
        assertEquals("提前 15 分钟提醒",
                ((TextView) a.findViewById(R.id.tv_pre_notice)).getText().toString());
        ctl.pause().stop().destroy();
    }

    /** 弹窗页：标题写「还有 1 小时」，副标题带名称与时刻。 */
    @Test
    public void popup_showsRemainingTimeAndAlarm() {
        reset();
        Intent i = new Intent(ctx(), PreNoticeActivity.class)
                .putExtra(PreNoticeReceiver.EXTRA_ALARM_ID, 1)
                .putExtra(PreNoticeReceiver.EXTRA_LABEL, "起床")
                .putExtra(PreNoticeReceiver.EXTRA_MINUTES, 60)
                .putExtra(PreNoticeReceiver.EXTRA_TIME_TEXT, "07:00");
        ActivityController<PreNoticeActivity> ctl =
                Robolectric.buildActivity(PreNoticeActivity.class, i).setup();
        ShadowLooper.idleMainLooper();

        TextView title = ctl.get().findViewById(R.id.tv_pre_title);
        TextView sub = ctl.get().findViewById(R.id.tv_pre_sub);
        assertEquals("还有 1 小时", title.getText().toString());
        assertEquals("起床 · 07:00 响铃", sub.getText().toString());

        ctl.get().findViewById(R.id.btn_pre_ok).performClick();
        assertTrue("点「知道了」应关闭弹窗", ctl.get().isFinishing());
        ctl.pause().stop().destroy();
    }

    /** 「关闭这次响铃」= 取消主闹钟 + 提前通知 + 小睡，并关掉弹窗。 */
    @Test
    public void popup_cancelClosesThisRing() {
        reset();
        Intent i = new Intent(ctx(), PreNoticeActivity.class)
                .putExtra(PreNoticeReceiver.EXTRA_ALARM_ID, 1)
                .putExtra(PreNoticeReceiver.EXTRA_LABEL, "起床")
                .putExtra(PreNoticeReceiver.EXTRA_DATE, "2026-10-21")
                .putExtra(PreNoticeReceiver.EXTRA_MINUTES, 30);
        ActivityController<PreNoticeActivity> ctl =
                Robolectric.buildActivity(PreNoticeActivity.class, i).setup();
        ShadowLooper.idleMainLooper();

        ctl.get().findViewById(R.id.btn_pre_cancel).performClick();
        assertTrue("点「关闭这次响铃」应关闭弹窗", ctl.get().isFinishing());
        ctl.pause().stop().destroy();
    }

    /** 存储层兜底：超过 60 的值一律夹到 60，避免脏数据排出「提前 999 分钟」。 */
    @Test
    public void prefs_clampsToOneHour() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 999);
        assertEquals(60, Prefs.getPreNoticeMinutes(ctx()));
    }

    // ================= 补发：在提前量窗口内才设的闹钟（v1.23.2）=================

    private void seedAlarm(int minutesFromNow) {
        LocalDateTime when = LocalDateTime.now().plusMinutes(minutesFromNow)
                .withSecond(0).withNano(0);
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit()
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true).commit();
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, when.getHour(), when.getMinute(), "午休", Alarm.Mode.DAILY, true)));
        Prefs.setEnabled(ctx(), true);
    }

    private Notification preNoticeNotification() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        return org.robolectric.Shadows.shadowOf(nm)
                .getNotification(PreNoticeReceiver.NOTIF_ID_BASE + 1);
    }

    /**
     * 14:31 设 15:31 的闹钟、提前 1 小时：提醒时刻已经过去，注册时会被跳过。
     * 重排必须补一条，否则这一次永远收不到提前通知。
     */
    @Test
    public void preNotice_isPosted_whenAlarmSetInsideWindow() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);   // 30 分钟后响，60 分钟的提前量早就过了
        ScheduleManager.rescheduleAll(ctx());

        assertNotNull("窗口内设的闹钟也应收到提前提醒", preNoticeNotification());
    }

    /** 还没进窗口（30 分钟后响、提前 10 分钟）就不该提前发。 */
    @Test
    public void preNotice_notPosted_beforeWindow() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 10);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        assertNull("还没到提醒时刻，不该提前发", preNoticeNotification());
    }

    /** 常驻通知：点进详情不会消失，也不能被划掉；按钮叫「关闭闹钟」。 */
    @Test
    public void preNotice_isOngoingWithCloseAction() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        assertEquals("应是常驻通知，直到响铃或关闭",
                Notification.FLAG_ONGOING_EVENT, n.flags & Notification.FLAG_ONGOING_EVENT);
        assertEquals("点通知进详情后不该自动消失",
                0, n.flags & Notification.FLAG_AUTO_CANCEL);
        assertNotNull("应有操作按钮", n.actions);
        assertEquals("关闭闹钟", n.actions[0].title.toString());
        // v1.23.3：icon 传 0 时 MIUI / 澎湃会整条 action 都不渲染，
        // 通知栏里只剩文字，按钮永远看不见
        assertNotNull("按钮必须带图标，否则 ROM 不显示这个按钮", n.actions[0].getIcon());
    }

    /**
     * v1.23.6：折叠态必须换成自绘布局。
     *
     * <p>锁屏卡片和「下拉但没展开」渲染的都是折叠态，系统模板里只放小图标 + 标题 + 一行正文，
     * 没有按钮的位置——addAction 的按钮要展开才出现。前几版锁屏上永远看不到「关闭闹钟」，
     * 根因就在这。设了 customContentView，折叠态才由我们的布局说了算。
     */
    @Test
    public void preNotice_collapsedCard_usesCustomLayout() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        assertNotNull("折叠态必须设 customContentView，否则锁屏上永远是系统模板", n.contentView);
        assertEquals("折叠态应使用 notif_content 自绘布局",
                R.layout.notif_content, n.contentView.getLayoutId());

        // v1.38.0：文案不再写死在 XML 里（同一个布局被三条通知共用），
        // 必须真的把 RemoteViews 应用出来看，才能验到运行时塞进去的字
        View card = n.contentView.apply(ctx(), new android.widget.FrameLayout(ctx()));
        TextView action = card.findViewById(R.id.nc_action);
        assertEquals("关闭闹钟", action.getText().toString());
        assertEquals("只有一个操作，第二个药丸应隐藏",
                View.GONE, card.findViewById(R.id.nc_action2).getVisibility());
    }

    /**
     * 倒计时必须画在卡片里（v1.38.0）。
     *
     * <p>v1.37.0 用 {@code setUsesChronometer} 让系统画在通知头部，但设了 customContentView 后
     * 头部整行都不再由系统渲染——用户「找不到倒计时」就是这个原因，那几行代码从没生效过。
     * 现在改成卡片里的 Chronometer：断言它可见、且 base 落在「还剩一小时」附近。
     *
     * <p>base 用 elapsedRealtime 时基，不是 wall clock。传错会显示成几十万小时，
     * 所以这里同时把「base 与 elapsedRealtime 的差」钉在合理范围内。
     */
    @Test
    public void preNotice_cardShowsLiveCountdown() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        View card = n.contentView.apply(ctx(), new android.widget.FrameLayout(ctx()));
        View chrono = card.findViewById(R.id.nc_chrono);
        assertEquals("有准确时间就该显示倒计时", View.VISIBLE, chrono.getVisibility());

        long remaining = ((android.widget.Chronometer) chrono).getBase()
                - android.os.SystemClock.elapsedRealtime();
        // 提前量 60 分钟，闹钟设在 30 分钟后 → 提醒在 30 分钟后，倒计时应接近 30 分钟
        assertTrue("倒计时剩余应接近 30 分钟，实际 " + remaining / 1000 + " 秒",
                remaining > 25 * 60_000L && remaining < 35 * 60_000L);
    }

    /**
     * v1.23.8：通知小图标必须是 App 自己的钟面。
     *
     * <p>旧图标圆心偏在 (12, 6.5)、只有一根针，跟启动图标的钟面 logo 完全不像。
     * 这里把资源钉住，避免以后又被换成别的图形。
     */
    @Test
    public void preNotice_usesAppClockIcon() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        assertNotNull("通知必须有状态栏小图标", n.getSmallIcon());
        assertEquals(R.drawable.ic_notification, n.getSmallIcon().getResId());
    }

    /**
     * 重复重排不该堆出多条。
     *
     * <p>v1.23.5 起重排会重发同一条（同 id 覆盖）来刷新样式，所以不再比较 when——
     * 它每次都会变。真正要守住的是「通知栏里只有一条」。
     */
    @Test
    public void preNotice_notDuplicatedOnReschedule() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());
        assertNotNull(preNoticeNotification());

        ScheduleManager.rescheduleAll(ctx());
        assertNotNull("重排后通知应还在", preNoticeNotification());
        assertEquals("重排不该堆出多条通知", 1, preNoticeCount());
    }

    /**
     * v1.23.5：单个闹钟被关掉后，它那条「即将响铃」必须撤掉。
     *
     * <p>之前只有响铃和关总开关会撤通知，于是用户在列表里关掉闹钟，
     * 通知栏里还挂着一条，点进去是个已停用的闹钟。
     */
    @Test
    public void disablingAlarm_dropsItsPreNotice() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());
        assertNotNull("先确认通知发出去了", preNoticeNotification());

        List<Alarm> list = Prefs.getAlarms(ctx());
        for (Alarm a : list) {
            a.enabled = false;
        }
        Prefs.setAlarms(ctx(), list);
        ScheduleManager.rescheduleAll(ctx());

        assertNull("闹钟停用后，通知栏不该还留着「即将响铃」", preNoticeNotification());
    }

    private int preNoticeCount() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        // 这个测试里只有提前提醒会发通知，直接数总数即可
        return org.robolectric.Shadows.shadowOf(nm).size();
    }

    /**
     * v1.23.4：横幅要自绘。
     *
     * <p>原生 heads-up 只渲染图标 / 标题 / 一行正文，不渲染 addAction——所以下拉通知栏
     * 能看到「关闭闹钟」，锁屏横幅上什么都没有。系统闹钟是 setCustomHeadsUpContentView
     * 把按钮画进布局才做到的，这里钉住「横幅确实挂了自绘布局」。
     */
    @Test
    public void preNotice_headsUpUsesCustomLayout() throws Exception {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        java.lang.reflect.Field f = Notification.class.getDeclaredField("headsUpContentView");
        f.setAccessible(true);
        Object hu = f.get(n);
        assertNotNull("横幅必须自绘，否则 heads-up 上没有「关闭闹钟」按钮", hu);
        assertEquals("应挂 notif_heads_up 布局",
                R.layout.notif_heads_up, ((android.widget.RemoteViews) hu).getLayoutId());
    }

    /**
     * v1.23.3：升级后要把旧版发的提前提醒清掉。
     *
     * <p>旧通知的 action 没有图标，ROM 不渲染按钮；不去重的话重排会以为「已经发过」
     * 而跳过补发，用户升级完按钮照样看不见。
     */
    @Test
    public void upgrade_dropsStalePreNotice() {
        reset();
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(PreNoticeReceiver.NOTIF_ID_BASE + 1,
                new Notification.Builder(ctx(), App.CHANNEL_PRE_NOTICE)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("旧版留下的通知")
                        .build());
        assertNotNull("先确认这条旧通知挂上了", preNoticeNotification());

        ScheduleManager.dropStalePreNotices(ctx());

        assertNull("升级后旧通知应被清掉，重排才会重发带图标的", preNoticeNotification());
    }

    // ================= v1.37.0：时间不能是死的 =================

    /**
     * 通知栏那行字必须是绝对时间（v1.37.0）。
     *
     * <p>原来写「1 小时后 · 14:10 响铃」，而通知发出去就不再刷新——躺到只剩 10 分钟时，
     * 它还在说「1 小时后」。相对时间只在发出那一刻成立，长期挂着只能写绝对时间。
     * 「还剩多久」交给头部的系统倒计时（下一条测）。
     */
    @Test
    public void preNotice_bodyIsAbsoluteTime_notRelative() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        String body = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue("正文要写清几点响，实际：" + body, body.endsWith("响铃"));
        assertFalse("不能再写「N 小时后」——它会随时间失真，实际：" + body,
                body.contains("小时后") || body.contains("分钟后"));
    }

    /**
     * 通知头部要挂系统倒计时（v1.37.0）。
     *
     * <p>setUsesChronometer + countDown 之后，SystemUI 每秒自己刷新「1:00:00」，
     * 不占我们的 CPU。这是唯一能真正「随时间变化」的做法——自己起服务刷新通知
     * 既费电，又会被国产 ROM 的后台限制掐掉。
     */
    @Test
    public void preNotice_usesSystemCountdown() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        assertTrue("要开倒计时", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue("倒计时方向应是「还剩」而不是「已过」",
                n.extras.getBoolean("android.chronometerCountDown"));
        assertTrue("when 应是触发时刻（未来），实际：" + n.when,
                n.when > System.currentTimeMillis());
    }

    /** 品牌紫：通知强调色要跟着 App 主色，不能是系统默认灰。 */
    @Test
    public void preNotice_usesBrandColor() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        assertEquals("强调色应为品牌紫", ctx().getColor(R.color.indigo), n.color);
    }

    /** 「今天」不写日期，「明天 / 后天」写出来，更远补月日——和弹窗副标题同一套措辞。 */
    @Test
    public void whenText_readsLikeHuman() {
        LocalDate today = LocalDate.now();
        assertEquals("14:10 响铃", PreNoticeReceiver.whenText(today, "14:10"));
        assertEquals("明天 14:10 响铃", PreNoticeReceiver.whenText(today.plusDays(1), "14:10"));
        assertEquals("后天 14:10 响铃", PreNoticeReceiver.whenText(today.plusDays(2), "14:10"));
        LocalDate far = today.plusDays(10);
        assertEquals(far.getMonthValue() + "月" + far.getDayOfMonth() + "日 14:10 响铃",
                PreNoticeReceiver.whenText(far, "14:10"));
        // 老数据没有时间文本时，宁可只说「即将响铃」，也别拼出「 响铃」
        assertEquals("即将响铃", PreNoticeReceiver.whenText(today, ""));
    }

    /** 横幅只出现几秒，可以写相对时间——「还有 10 分钟」比「14:10」更催人。 */
    @Test
    public void headsUpText_keepsRelativeTime() {
        assertEquals("1 小时后 · 14:10", PreNoticeReceiver.headsUpText(60, "14:10"));
        assertEquals("10 分钟后 · 09:30", PreNoticeReceiver.headsUpText(10, "09:30"));
        assertEquals("5 分钟后响铃", PreNoticeReceiver.headsUpText(5, ""));
    }

    /** 时间戳解析：正常值算对，脏数据一律返回 0（调用方据此跳过倒计时）。 */
    @Test
    public void triggerAt_parsesOrGivesUp() {
        LocalDate d = LocalDate.of(2026, 10, 22);
        long ms = PreNoticeReceiver.triggerAt(d, "14:10");
        assertEquals(d.atTime(14, 10).atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli(), ms);

        assertEquals("空串", 0, PreNoticeReceiver.triggerAt(d, ""));
        assertEquals("格式不对", 0, PreNoticeReceiver.triggerAt(d, "14:10:00"));
        assertEquals("非数字", 0, PreNoticeReceiver.triggerAt(d, "ab:cd"));
        assertEquals("null 日期", 0, PreNoticeReceiver.triggerAt(null, "14:10"));
    }
}
