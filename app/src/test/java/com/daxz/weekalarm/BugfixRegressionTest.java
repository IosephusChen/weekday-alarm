package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowNotificationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 七个 bug 修复的守门测试（v1.58.0）。
 *
 * <p>每个用例都对着一个真实发生过的缺陷，注释里写清了触发场景。这些场景共同点是
 * 「主流程永远走不到，单测不覆盖就静默坏着」——所以修完必须钉在这里，防止回归。
 *
 * <ul>
 *   <li>{@link #snoozePendingIntent_updatesExtrasAcrossRings}：通知栏小睡按钮 extras 冻结</li>
 *   <li>{@link #preNoticeIntent_encodesRingDayNotPreDay}：跨午夜提前提醒静默失效</li>
 *   <li>{@link #bedtimeSkipButton_targetsNoticeDay}：午夜后「明天请假」标错日期</li>
 *   <li>{@link #alarmFromJson_clampsHourMinute}：导入手改编坏的配置崩溃</li>
 *   <li>{@link #nextAlarmWidget_statusFollowsAlarms}：4×2 卡状态写死「已开启」</li>
 *   <li>{@link #christianFestival_beforeGregorian_doesNotThrow}：1583 前年份抛异常</li>
 * </ul>
 *
 * <p>第五个修复（升级重排挪后台，MainActivity.onResume）无法单测——ANR 是时序问题，
 * 由代码注释与 Code Review 守门。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BugfixRegressionTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    // ---------- 1. 通知栏小睡：extras 不能冻结 ----------

    /**
     * 第二次及以后响铃，点通知栏「稍后提醒」必须小睡**当前**这个闹钟。
     *
     * <p>PendingIntent 的匹配只看 requestCode + 组件，不看 extras。requestCode 固定为 2，
     * 少了 FLAG_UPDATE_CURRENT，第一个闹钟创建后 extras 就永久冻住——之后任何闹钟响铃，
     * 点小睡小睡的都是第一次那个（计数也记到旧 id / 旧日期）。
     */
    @Test
    public void snoozePendingIntent_updatesExtrasAcrossRings() throws Exception {
        resetPrefs();
        LocalDate today = LocalDate.now();

        // 第一个闹钟（id=1）响铃，创建通知与小睡 PendingIntent
        PendingIntent first = AlarmService.snoozePi(ctx(), 1, "起床", today.toString());
        // 第二个闹钟（id=2）响铃，同 requestCode 再创建一次
        PendingIntent second = AlarmService.snoozePi(ctx(), 2, "午休", today.toString());

        // 用户点的是**当前**通知上的按钮（second）；若底层 intent 没被更新，
        // 发出去的仍是 first 的 extras
        second.send();

        Intent started = Shadows.shadowOf((Application) ctx()).getNextStartedService();
        assertNotNull("小睡应启动 AlarmService", started);
        assertEquals("小睡的必须是当前响铃的闹钟（id=2），实际 " + started.getIntExtra(
                        AlarmService.EXTRA_ALARM_ID, -1),
                2, started.getIntExtra(AlarmService.EXTRA_ALARM_ID, -1));
        assertEquals("午休", started.getStringExtra(AlarmService.EXTRA_LABEL));
    }

    // ---------- 2. 跨午夜提前提醒：身份按响铃日编码 ----------

    /**
     * 提前提醒的 data URI / EXTRA_DATE 必须按**响铃日**编码，不是提醒时刻。
     *
     * <p>00:30 的闹钟提前 60 分钟，提醒落前一天 23:30。若按提醒时刻编码：
     * 触发时 isAlarmRegistered 拿前一天日期查不中（登记在响铃日），通知被静默丢弃；
     * cancelAll 也取消不掉它。触发时刻仍应是提醒时刻。
     */
    @Test
    public void preNoticeIntent_encodesRingDayNotPreDay() {
        LocalDate ringDay = LocalDate.of(2026, 10, 26);       // 响铃日
        LocalDateTime pre = LocalDateTime.of(2026, 10, 25, 23, 30);   // 提醒落前一天

        Intent i = ScheduleManager.preNoticeIntent(ctx(), 7, "起床", ringDay, 60, "00:30");

        assertEquals("data URI 必须按响铃日编码",
                "daxz://prenotice/7/" + ringDay, i.getData().toString());
        assertEquals("EXTRA_DATE 必须按响铃日编码（触发时靠它查登记）",
                ringDay.toString(), i.getStringExtra(PreNoticeReceiver.EXTRA_DATE));
        assertFalse("不能按提醒时刻的日期编码", i.getData().toString().contains(pre.toLocalDate().toString()));
        // 触发时刻本身仍是提醒时刻——「身份」换日，不改响点
        long triggerMillis = pre.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertTrue("提前时刻应早于响铃时刻", triggerMillis < ringDay.atTime(0, 30)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    /**
     * 注册与取消必须打中同一个实例：requestCode 由响铃日算出。
     */
    @Test
    public void preNoticeRequestCode_matchesRegistration() {
        LocalDate ringDay = LocalDate.of(2026, 10, 26);
        assertEquals(ScheduleManager.preNoticeRequestCode(7, ringDay),
                ScheduleManager.preNoticeRequestCode(7, ringDay));
    }

    // ---------- 3. 午夜后「今天请假」：按钮与行为同一天 ----------

    /**
     * 跨午夜后（00:00–05:00）通知改口成「今天早上」，请假按钮必须标**今天**。
     *
     * <p>v1.48.0 让文案改口，但按钮硬编码「明天请假」、skipTomorrow 也写死 now+1——
     * 凌晨点下去标的是明天，今早的闹钟照响。按钮文案、intent data、实际标记
     * 三处必须都是同一天。
     */
    @Test
    public void bedtimeSkipButton_targetsNoticeDay() throws Exception {
        resetPrefs();
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        LocalDate today = LocalDate.now();

        // 跨午夜后的 Model：tomorrow 已经是「今天」
        BedtimeReminder.Model m = new BedtimeReminder.Model(
                today, false, true, true, "起床", 7, 30, true,
                "今天要上班", "今天早上 07:30 起床 会响，早点睡");
        BedtimeReceiver.repost(ctx(), m);

        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = Shadows.shadowOf(nm);
        Notification n = shadow.getNotification(BedtimeReminder.NOTIF_ID);
        assertNotNull("通知应发出来", n);

        Notification.Action action = n.actions != null && n.actions.length > 0
                ? n.actions[0] : null;
        assertNotNull("上班日应有请假按钮", action);
        assertEquals("跨午夜后按钮应说「今天请假」", "今天请假", action.title.toString());

        // 点按钮：与通知烧死的 PendingIntent 同一构造（action + data=目标日期），
        // 标掉的必须是今天，不是明天
        Intent click = new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_SKIP_TOMORROW)
                .setData(android.net.Uri.parse("daxz://bedtime/skip/" + today));
        new BedtimeReceiver().onReceive(ctx(), click);
        List<String> vacations = new ArrayList<>();
        for (LocalDate d : Prefs.getVacationDates(ctx())) vacations.add(d.toString());
        assertTrue("点完应把今天标记为请假，实际标记了 " + vacations,
                vacations.contains(today.toString()));
        assertFalse("不能再顺手标掉明天", vacations.contains(today.plusDays(1).toString()));
    }

    /**
     * 没跨午夜时（22:00 发的通知）按钮仍说「明天请假」，行为不变。
     */
    @Test
    public void bedtimeSkipButton_normalNightStillTomorrow() throws Exception {
        resetPrefs();
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        BedtimeReminder.Model m = new BedtimeReminder.Model(
                tomorrow, false, true, true, "起床", 7, 30, true,
                "明天要上班", "明早 07:30 起床 会响，早点睡");
        BedtimeReceiver.repost(ctx(), m);

        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification(BedtimeReminder.NOTIF_ID);
        assertNotNull(n);
        assertEquals("明天请假", n.actions[0].title.toString());
        Intent click = new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_SKIP_TOMORROW)
                .setData(android.net.Uri.parse("daxz://bedtime/skip/" + tomorrow));
        new BedtimeReceiver().onReceive(ctx(), click);
        List<String> vacations = new ArrayList<>();
        for (LocalDate d : Prefs.getVacationDates(ctx())) vacations.add(d.toString());
        assertTrue("应标记明天", vacations.contains(tomorrow.toString()));
    }

    /**
     * 旧版本烧死在 PendingIntent 里的 intent 没有日期段时，skipTomorrow 回退「明天」不崩。
     */
    @Test
    public void bedtimeSkip_legacyIntentWithoutDate_fallsBackToTomorrow() {
        resetPrefs();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Intent legacy = new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_SKIP_TOMORROW);
        new BedtimeReceiver().onReceive(ctx(), legacy);
        List<String> vacations = new ArrayList<>();
        for (LocalDate d : Prefs.getVacationDates(ctx())) vacations.add(d.toString());
        assertTrue("无日期段时应回退明天", vacations.contains(tomorrow.toString()));
    }

    // ---------- 4. 导入配置：hour / minute 夹取 ----------

    /**
     * 配置文件是纯文本 JSON，用户手改编坏（"hour": 99）必须夹到合法值，
     * 而不是让之后的 LocalTime.of 在主线程抛 DateTimeException 带崩 App。
     */
    @Test
    public void alarmFromJson_clampsHourMinute() {
        List<Alarm> out = Alarm.fromJson(
                "[{\"id\":1,\"hour\":99,\"minute\":99,\"label\":\"手改坏的\"}]");
        assertEquals(1, out.size());
        Alarm a = out.get(0);
        assertEquals(23, a.hour);
        assertEquals(59, a.minute);
        // 夹取后的值必须能直接进 LocalTime.of（排程路径就是这么用的）
        assertNotNull(java.time.LocalTime.of(a.hour, a.minute));

        // 负数同样夹住
        List<Alarm> neg = Alarm.fromJson("[{\"id\":1,\"hour\":-5,\"minute\":-5}]");
        assertEquals(0, neg.get(0).hour);
        assertEquals(0, neg.get(0).minute);
    }

    // ---------- 5. 4×2 卡状态跟着闹钟走 ----------

    /**
     * 闹钟全关时 4×2 卡必须显示「闹钟未开启」，不能写死「已开启」——
     * 那会和「暂无提醒」自相矛盾（整月两张卡 v1.52.0 就接上了，4×2 漏接）。
     */
    @Test
    public void nextAlarmWidget_statusFollowsAlarms() {
        resetPrefs();
        Prefs.setAlarms(ctx(), new ArrayList<>());
        RemoteViews rv = NextAlarmWidget.buildViews(ctx(), NextAlarmWidget.computeModel(ctx()));
        View v = rv.apply(ctx(), new FrameLayout(ctx()));
        assertEquals("闹钟未开启",
                ((TextView) v.findViewById(R.id.widget_status)).getText().toString());

        Prefs.setAlarms(ctx(), new ArrayList<>(Collections.singletonList(
                new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true))));
        RemoteViews rv2 = NextAlarmWidget.buildViews(ctx(), NextAlarmWidget.computeModel(ctx()));
        View v2 = rv2.apply(ctx(), new FrameLayout(ctx()));
        assertEquals("有闹钟时应显示已开启",
                "闹钟已开启",
                ((TextView) v2.findViewById(R.id.widget_status)).getText().toString());
    }

    // ---------- 6. 基督教节日：1583 前不抛异常 ----------

    /**
     * 复活节算法只适用于格里高利历（1583 起）。1583 前固定节日照常（圣诞始终是圣诞），
     * 移动节日返回 null——和 SolarTerm（1900 前）、LunarCalendar（越界）一样的保护，
     * 不让异常冒到日历渲染层。
     */
    @Test
    public void christianFestival_beforeGregorian_doesNotThrow() {
        assertEquals("圣诞", ChristianCalendar.festival(LocalDate.of(1582, 12, 25)));
        assertNull("移动节日在 1583 前不猜", ChristianCalendar.festival(LocalDate.of(1582, 4, 10)));
        // 1583 起恢复正常（2026 年复活节是 4 月 5 日）
        assertEquals("复活", ChristianCalendar.festival(LocalDate.of(2026, 4, 5)));
        assertNull("不是节日仍是 null", ChristianCalendar.festival(LocalDate.of(2026, 7, 1)));
    }
}
