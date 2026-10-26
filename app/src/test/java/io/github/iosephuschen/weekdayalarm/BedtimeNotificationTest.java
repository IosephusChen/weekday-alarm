package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
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
import android.widget.RadioButton;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 睡前提醒「说过的话要跟着改」+ 界面外的状态变更要立刻反映到界面上（v0.56.6）。
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
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
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

    // ---------- 没人处理的通知要自己消失（v0.58.8）----------

    /**
     * 用户不点也不划时，这条提醒不能一直挂着。
     *
     * <p>主机制是通知自带的过期时间：到次日 05:00 由系统移除，App 从没被打开也一样。
     */
    @Test
    public void notice_expiresNextMorning() {
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));

        Notification n = shown();
        assertNotNull(n);
        long timeout = n.getTimeoutAfter();
        // 不能和 millisUntilNextMorning() 比精确值：两次调用之间已经过去几毫秒
        assertTrue("过期时间必须是个正数", timeout > 0);
        assertTrue("应落在次日 05:00 附近，实际 " + timeout + "ms",
                Math.abs(timeout - BedtimeReceiver.millisUntilNextMorning()) < 5000);
        assertTrue("至少给一小时，不能刚发出就消失", timeout >= 60L * 60 * 1000 - 5000);
    }

    /**
     * 跨过午夜、但还在凌晨：不撤，改成「今天」（v0.59.0）。
     *
     * <p>昨晚写的是「明天 07:30 起床」，过了 00:00 它指的已经是今天——
     * 同一句话差了一天，而这行字正对着睡醒前看手机的人。
     */
    @Test
    public void refresh_afterMidnight_saysToday() {
        // 这条测的是「跨天之后文案要改口成今天」，跟「只在工作日提醒」无关。
        // 关掉工作日过滤，否则测试会隐式依赖「今天恰好是工作日」——周六跑必红。
        Prefs.setBedtimeWorkdayOnly(ctx(), false);

        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));
        assertNotNull(shown());

        // 日期戳是昨天 + 固定凌晨 01:00，模拟「这条挂过了午夜」
        Prefs.setBedtimeNoticeDay(ctx(), LocalDate.now().minusDays(1).toString());
        BedtimeReminder.refreshIfShownAt(ctx(), LocalDate.now(), LocalTime.of(1, 0));

        assertNotNull("凌晨还该留着", shown());
        String text = shownText();
        assertTrue("应改口成「今天」，实际：" + text, text.contains("今天"));
        assertFalse("不该再说「明天」，实际：" + text, text.contains("明天"));
    }

    /**
     * 跨天且已过 05:00：撤掉。正常情况它早被系统按过期时间移除了，
     * 这条防的是不守 timeoutAfter 的 ROM。
     */
    @Test
    public void refresh_afterMorning_dropsNotice() {
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));
        assertNotNull(shown());

        Prefs.setBedtimeNoticeDay(ctx(), LocalDate.now().minusDays(1).toString());
        BedtimeReminder.refreshIfShownAt(ctx(), LocalDate.now(), LocalTime.of(9, 0));

        assertNull("过了这一夜就不该留着", shown());
    }

    /**
     * 文案里的相对词跟着日期走：说今天就是「今天」，说明天才是「明天」。
     *
     * <p>这条钉住的是「别再把词写死」——v0.59.0 之前 computeModel 里四处硬编码「明天」，
     * 跨天之后无法改口。
     */
    @Test
    public void computeModel_usesRelativeWordOfTargetDay() {
        String today = BedtimeReminder.computeModel(ctx(), LocalDate.now()).text;
        String tomorrow = BedtimeReminder.computeModel(ctx(), LocalDate.now().plusDays(1)).text;

        assertTrue("按今天算应该是「今天」，实际：" + today, today.startsWith("今天"));
        assertFalse("按今天算不该出现「明天」，实际：" + today, today.contains("明天"));
        // 明天是工作日时正文以「明早」开头，休息日以「明天」开头——都不能说成「今天」
        assertTrue("按明天算应说明天，实际：" + tomorrow,
                tomorrow.startsWith("明天") || tomorrow.startsWith("明早"));
        assertFalse("按明天算不该出现「今天」，实际：" + tomorrow, tomorrow.startsWith("今天"));
    }

    /**
     * 起床闹钟一响，昨晚那条就该消失——它已经兑现了。
     *
     * <p>这条同时是「过期时间之外的第二道保险」：万一某个 ROM 不守 timeoutAfter。
     */
    @Test
    public void ringingCancelsNotice() {
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));
        assertNotNull(shown());

        BedtimeReminder.cancelNotice(ctx());

        assertNull("响铃后不该还留着昨晚的提醒", shown());
    }

    /**
     * 提前通知一出现，睡前提醒就该退场（v0.59.0）。
     *
     * <p>闹钟 05:00、提前 30 分钟，04:30 那条「即将响铃」出来时，05:00 的兜底还没到；
     * 此时两条并排挂着，前者说「今天早上 05:00 起床」，后者说「还有 30 分钟」——
     * 说的是同一件事，用户会以为是两个闹钟。
     */
    @Test
    public void preNotice_cancelsBedtimeNotice() {
        // 造一条 40 分钟后响的闹钟，并让系统里真的登记上（post 会检查 isAlarmRegistered）
        java.time.LocalDateTime target = java.time.LocalDateTime.now()
                .plusMinutes(40).withSecond(0).withNano(0);
        Alarm a = new Alarm(7, target.getHour(), target.getMinute(), "起床",
                Alarm.Mode.ONCE, true, target.toLocalDate().toString());
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(a));
        ScheduleManager.rescheduleAll(ctx());

        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));
        assertNotNull(shown());

        PreNoticeReceiver.post(ctx(), 7, "起床", target.toLocalDate(), 30,
                target.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        assertNull("提前通知出现后，昨晚那条该退场", shown());
    }

    /**
     * 关掉功能之后，已经挂着的那条要撤（v0.59.1）。
     *
     * <p>缺口场景：22:00 收到提醒、22:05 去设置里关掉开关。以前只取消了下一天的调度，
     * 当前这条会一直留到 05:00 过期——关了却还在，看着像没关掉。
     */
    @Test
    public void switchingOffInSettings_cancelsShownNotice() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));
        assertNotNull(shown());

        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        ctl.get().findViewById(R.id.row_bedtime).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出睡前提醒设置", d);
        // 选「关闭」当场生效并收起对话框（没有确定按钮）。
        // v0.78.0 卡片化后选项不再是 RadioButton，按位置取第一张卡：
        // 顺序固定为 关闭 / 21:30 / 22:00 / 22:30 / 23:00 / 其他时间…
        ViewGroup list = d.findViewById(R.id.dlg_bed_list);
        assertNotNull("应有时间选项列表", list);
        assertEquals("六个时间选项", 6, list.getChildCount());
        list.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(Prefs.BEDTIME_OFF, Prefs.getBedtimeMinutes(ctx()));
        assertNull("关掉开关后，已挂的通知要立刻撤掉", shown());
        ctl.pause().stop().destroy();
    }

    /**
     * 功能关着的时候，任何刷新都只能是「撤掉」。
     *
     * <p>否则关掉开关后，一次界面刷新（改闹钟、标请假）又会按「明天要上班」把它发回来。
     */
    @Test
    public void refresh_whenFeatureOff_cancelsNotice() {
        BedtimeReceiver.repost(ctx(), model(true, "明天要上班", "明早 07:30 起床 会响"));
        assertNotNull(shown());

        Prefs.setBedtimeMinutes(ctx(), Prefs.BEDTIME_OFF);
        BedtimeReminder.refreshIfShownAt(ctx(), LocalDate.now(), LocalTime.of(22, 30));

        assertNull("功能已关，刷新不该把它留在栏里", shown());
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
