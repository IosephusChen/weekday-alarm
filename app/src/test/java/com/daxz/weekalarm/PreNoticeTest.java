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
import android.view.View;
import android.widget.TextView;

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
import java.time.format.DateTimeFormatter;
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

    /** v1.40.0：不足 1 分钟不能写「0 分钟」，90 分钟不能截成「1 小时」。 */
    @Test
    public void minutesText_handlesSubMinuteAndRemainder() {
        assertEquals("不到 1 分钟", PreNoticeReceiver.minutesText(0));
        assertEquals("1 小时 30 分", PreNoticeReceiver.minutesText(90));
    }

    /**
     * 「还有多久」必须按真实时间算，不能照抄排程时的提前量。
     *
     * <p>用户撞上过：4:23 弹出「还有 1 小时」，闹钟 4:24 就响。原因是闹钟在提前量窗口内
     * 才设，重排时立刻补发一条，而文案里的分钟数还是配置的 60。
     */
    @Test
    public void actualMinutes_usesRealRemainingTime() {
        // v1.71.3：日期跟着时刻一起取，不能只取 HH:mm。
        // 「现在 +5 分钟」在 23:55 之后会跨到明天，日期却还是今天，
        // 于是算出来是负的、返回 0——这条测试在午夜前五分钟必红
        // （v1.71.3 的恢复演练正好撞上 23:58）。
        LocalDateTime soon = LocalDateTime.now().plusMinutes(5);
        int m = PreNoticeReceiver.actualMinutes(soon.toLocalDate(),
                soon.format(DateTimeFormatter.ofPattern("HH:mm")), 60);
        assertTrue("还剩约 5 分钟，不能报 60（实际 " + m + "）", m >= 4 && m <= 6);

        // 已经到点 / 过点：返回 0，调用方据此不再补发过期的预告
        LocalDateTime past = LocalDateTime.now().minusMinutes(3);
        assertEquals(0, PreNoticeReceiver.actualMinutes(past.toLocalDate(),
                past.format(DateTimeFormatter.ofPattern("HH:mm")), 60));

        // 文案缺失时回退到配置值
        assertEquals(60, PreNoticeReceiver.actualMinutes(LocalDate.now(), "", 60));
    }

    /**
     * 设置页的提前提醒对话框要有「提前 1 小时」，且存进去的是 60。
     *
     * <p>v1.70.0：弹窗换成单选卡片（程序化建视图，没有布局可 inflate），
     * 改成在卡片列表里按文案找卡。
     * v1.71.8：从「选完按确定」改成「点一项即生效并关窗」——它只有一组选项，
     * 不需要二次确认，而同组的睡前提醒早就是点即生效。
     */
    @Test
    public void preNoticeDialog_hasOneHourOption() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 0);
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        // v1.72.1：整行点开对话框（开关已去掉），不再要点副标题那一小块
        ctl.get().findViewById(R.id.row_pre_notice).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出提前提醒设置", d);
        View hour = findOptionCard(d, "提前 1 小时");
        assertNotNull("缺少「提前 1 小时」档位", hour);

        hour.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("选「提前 1 小时」应存成 60 分钟", 60, Prefs.getPreNoticeMinutes(ctx()));
        assertFalse("点一项就该关窗（不再有确定按钮）", d.isShowing());
        ctl.pause().stop().destroy();
    }

    /** 已经设成 60 时，再打开对话框要选中「提前 1 小时」，而不是退回 30。 */
    @Test
    public void preNoticeDialog_restoresOneHourSelection() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        // v1.72.1：整行点开对话框（开关已去掉），不再要点副标题那一小块
        ctl.get().findViewById(R.id.row_pre_notice).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        View hour = findOptionCard(d, "提前 1 小时");
        assertNotNull(hour);
        // v1.70.0：选中态由 setSelected 驱动（bg_guide_option 的描边与底色），
        // 只打勾不变色的话当前值看上去和没选中的项没区别
        assertTrue("存了 60 分钟就应选中「提前 1 小时」", hour.isSelected());
        assertFalse("没选的项不该是选中态",
                findOptionCard(d, "提前 30 分钟").isSelected());
        ctl.pause().stop().destroy();
    }

    /**
     * 在单选卡片对话框里按主文案找卡。
     *
     * <p>{@link DialogKit#optionView} 的卡片结构固定：外层横排 LinearLayout，
     * 第 0 个子视图是竖排文字列，文字列第 0 个是主文案 TextView。
     * 组标题（TextView）没有文字列，按结构跳过即可。
     */
    private static View findOptionCard(Dialog d, String text) {
        android.widget.LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            View col = ((android.widget.LinearLayout) child).getChildAt(0);
            if (!(col instanceof android.widget.LinearLayout)) continue;
            TextView tv = (TextView) ((android.widget.LinearLayout) col).getChildAt(0);
            if (text.contentEquals(tv.getText())) return child;
        }
        return null;
    }

    /**
     * v1.72.1：这一行右侧从开关换成「值 + 箭头」。
     *
     * <p>换掉开关的原因是可发现性——原来整行点击是开关、只有副标题那一小块
     * （约 60dp 宽）能改提前量，用户看不出这里还能设置。现在值直接摆在右边，
     * 关掉写「关闭」，与「周休制度」那行同一套语言。
     */
    @Test
    public void rowValue_followsMinutes() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 15);
        SettingsActivity on = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        assertEquals("提前 15 分钟",
                ((TextView) on.findViewById(R.id.tv_pre_notice_value)).getText().toString());
        assertEquals("副标题讲机制，不重复值",
                "响铃前先在通知栏提醒一次",
                ((TextView) on.findViewById(R.id.tv_pre_notice)).getText().toString());

        Prefs.setPreNoticeMinutes(ctx(), 0);
        SettingsActivity off = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals("关掉时值应写「关闭」",
                "关闭",
                ((TextView) off.findViewById(R.id.tv_pre_notice_value)).getText().toString());
        assertEquals("关掉后副标题仍是同一句机制说明",
                "响铃前先在通知栏提醒一次",
                ((TextView) off.findViewById(R.id.tv_pre_notice)).getText().toString());
    }

    /**
     * 弹窗页：标题写「还有 X」，副标题带名称与时刻。
     *
     * <p>v1.40.0 起标题按「现在到响铃时刻」现算，所以这里的时间必须相对 now 生成；
     * 写死 07:00 的话，凌晨跑测试会得到「还有 2 小时」——那是正确行为，测试错。
     *
     * <p>断言只能给一个范围：extras 里的时刻精确到分钟（HH:mm），「现在 + 60 分钟」
     * 截断到分钟之后，真实剩余落在 59:00～60:00 之间，向上取整就是 59 或 60。
     * 两个都算对——这条测的是「按真实时间算、且说人话」，不是测取整函数
     * （取整由 {@code actualMinutes_usesRealRemainingTime} 单独钉）。
     */
    @Test
    public void popup_showsRemainingTimeAndAlarm() {
        reset();
        java.time.LocalDateTime target = java.time.LocalDateTime.now()
                .plusMinutes(60).withSecond(0).withNano(0);
        Intent i = new Intent(ctx(), PreNoticeActivity.class)
                .putExtra(PreNoticeReceiver.EXTRA_ALARM_ID, 1)
                .putExtra(PreNoticeReceiver.EXTRA_LABEL, "起床")
                .putExtra(PreNoticeReceiver.EXTRA_MINUTES, 60)
                .putExtra(PreNoticeReceiver.EXTRA_DATE, target.toLocalDate().toString())
                .putExtra(PreNoticeReceiver.EXTRA_TIME_TEXT,
                        target.format(DateTimeFormatter.ofPattern("HH:mm")));
        ActivityController<PreNoticeActivity> ctl =
                Robolectric.buildActivity(PreNoticeActivity.class, i).setup();
        ShadowLooper.idleMainLooper();

        TextView title = ctl.get().findViewById(R.id.tv_pre_title);
        TextView sub = ctl.get().findViewById(R.id.tv_pre_sub);
        String titleText = title.getText().toString();
        assertTrue("标题应是「还有 X」的形式，实际：" + titleText,
                titleText.equals("还有 1 小时") || titleText.equals("还有 59 分钟"));
        assertTrue("副标题应带名称与时刻，实际：" + sub.getText(),
                sub.getText().toString().startsWith("起床 · ")
                        && sub.getText().toString().endsWith(" 响铃"));

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

    /** 常驻通知：点进详情不会消失，也不能被划掉；按钮叫「这次不响」。 */
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
        assertEquals("这次不响", n.actions[0].title.toString());
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
        assertEquals("这次不响", action.getText().toString());
        assertEquals("只有一个操作，第二个药丸应隐藏",
                View.GONE, card.findViewById(R.id.nc_action2).getVisibility());
    }

    /**
     * 卡片里**不该**再有读秒倒计时（v1.45.0）。
     *
     * <p>演进：v1.37.0 用 {@code setUsesChronometer} 让系统画在通知头部（设了自绘布局后
     * 从没生效过）→ v1.38.0 改由卡片里的 Chronometer 承担 → v1.45.0 用户说「读秒没必要」
     * 干脆去掉：它每秒跳一次，眼睛会一直往那儿瞟，比一句「14:10 响铃」更吵。
     *
     * <p>这条测试钉的是「去掉之后别再被加回来」。要恢复的话，先想清楚
     * 用户为什么要求去掉——不是嫌它不准，是嫌它吵。
     */
    @Test
    public void preNotice_cardHasNoCountdown() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        View card = n.contentView.apply(ctx(), new android.widget.FrameLayout(ctx()));
        View chrono = card.findViewById(R.id.nc_chrono);
        assertEquals("提前提醒不该显示读秒倒计时", View.GONE, chrono.getVisibility());
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
     * 通知头部的系统倒计时也要关掉（v1.45.0）。
     *
     * <p>v1.37.0 开过 {@code setUsesChronometer}，虽然设了自绘布局后它从没渲染出来，
     * 但只要留着，哪天有人换回系统模板它就又冒出来了。既然用户不要读秒，两处都清掉。
     *
     * <p>时间戳 {@code when} 保留：下拉展开后的系统头部会用它显示「14:10」，
     * 那不是读秒，是有用的信息。
     */
    @Test
    public void preNotice_hasNoSystemChronometer() {
        reset();
        Prefs.setPreNoticeMinutes(ctx(), 60);
        seedAlarm(30);
        ScheduleManager.rescheduleAll(ctx());

        Notification n = preNoticeNotification();
        assertNotNull(n);
        assertFalse("不该再开系统倒计时", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue("when 仍是触发时刻（未来），实际：" + n.when,
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
