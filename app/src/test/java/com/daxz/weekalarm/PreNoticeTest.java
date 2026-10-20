package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
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

        // 布局里那个药丸就是按钮，文字必须对得上
        TextView action = LayoutInflater.from(ctx())
                .inflate(R.layout.notif_content, null)
                .findViewById(R.id.nc_action);
        assertEquals("关闭闹钟", action.getText().toString());
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
}
