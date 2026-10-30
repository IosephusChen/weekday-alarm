package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.time.LocalDate;
import java.util.Collections;

/**
 * 小睡的生命周期：谁排的、谁该作废（v0.91.8）。
 *
 * <p>起点是真机上一次完整的复现：
 *
 * <pre>
 * 12:36  A 响 → 点「稍后提醒」→ 排下 12:41、计数 1、发「12:41 再次响铃」
 * 之后   把 A 改成 12:38 —— 上面三样一样都没清
 * 12:33  提前提醒照发「即将响铃 12:38」，通知栏同时挂着两条
 * 12:38  A 响，全屏页写着「还能睡 2 次」（沿用上一轮用掉的那一格）
 * </pre>
 *
 * <p>语义只有一句：<b>用户重新安排了时间，上一轮作废</b>。小睡、计数、那条告知性通知
 * 都是上一轮的残留，留着任何一个，界面上说的都和他刚做的决定相反。
 *
 * <p>另一头是「响了就说明这一轮结束」：正常响铃开始时也要把待执行的小睡掐掉。
 * 这一条尤其不能靠 ROM 恰好压住——真机上 12:41 那次确实没响，但代码里找不到
 * 任何一处取消它的地方，同一套操作再做一次可能就响了。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SnoozeLifecycleTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private ShadowNotificationManager notifMgr() {
        return shadowOf((NotificationManager)
                ctx().getSystemService(Context.NOTIFICATION_SERVICE));
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setSnoozeMinutes(ctx(), 5);
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true);
        Prefs.setAlarms(ctx(), Collections.singletonList(a));
    }

    /**
     * 改时间要作废三样：排程、计数、那条通知。
     *
     * <p>少清任何一样，用户都会看到「界面说的话」和「它要做的事」不一致。
     */
    @Test
    public void dropSnooze_clearsScheduleCountAndNotice() {
        LocalDate today = LocalDate.now();
        ScheduleManager.snooze(ctx(), 1, "起床");
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        SnoozeNotice.post(ctx(), 1, "起床", 5);

        assertTrue("前提：小睡排上了", ScheduleManager.isSnoozeRegistered(ctx(), 1, today));
        assertEquals("前提：用掉了一格", 1, Prefs.getSnoozeCount(ctx(), 1, today));

        ScheduleManager.dropSnooze(ctx(), 1);

        assertFalse("改时间后上一轮排下的小睡必须作废，否则它还会在旧时刻响",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, today));
        assertEquals("计数要归零，新一轮才有完整的次数",
                0, Prefs.getSnoozeCount(ctx(), 1, today));
        assertNull("那条「12:41 再次响铃」也得撤，不能和新的提前提醒并排挂着",
                notifMgr().getNotification(SnoozeNotice.id(1)));
    }

    /**
     * 正常响铃开始时，待执行的小睡要掐掉。
     *
     * <p>只取消排程，<b>不动计数</b>：这次响铃本身可能就是小睡触发的，
     * 清计数等于把用掉的次数还回去，用户可以无限续睡。
     */
    @Test
    public void ringing_cancelsPendingSnoozeButKeepsCount() {
        LocalDate today = LocalDate.now();
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        ScheduleManager.snooze(ctx(), 1, "起床");
        assertTrue("前提：小睡排上了", ScheduleManager.isSnoozeRegistered(ctx(), 1, today));

        Robolectric.buildService(AlarmService.class,
                        new Intent(ctx(), AlarmService.class)
                                .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                                .putExtra(AlarmService.EXTRA_LABEL, "起床")
                                .putExtra(AlarmService.EXTRA_DATE, today.toString()))
                .startCommand(0, 0);

        assertFalse("响了就说明这一轮结束了，之前排下的那趟不该再补一次",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, today));
        assertNotEquals("但计数不能清——这次响可能就是小睡触发的",
                0, Prefs.getSnoozeCount(ctx(), 1, today));
    }

    /**
     * 编辑页保存时确实调了作废（源码级断言）。
     *
     * <p>为什么不去点对话框：改时间要操作 TimePicker，而这条测试要钉的是
     * 「保存路径上有没有这个调用」，不是 TimePicker 的滚轮。AlarmEditor 里那段
     * 还带着「只改铃声不清小睡」的分支，靠读源码钉住比造一条走不到分支的用例更实在。
     */
    @Test
    public void editorSave_callsDropSnooze() throws Exception {
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        while (dir != null && !new java.io.File(dir, "app/src/main").isDirectory()) {
            dir = dir.getParentFile();
        }
        assertTrue("找不到仓库根目录", dir != null);
        java.io.File srcFile = new java.io.File(dir,
                "app/src/main/java/io/github/iosephuschen/weekdayalarm/AlarmEditor.java");
        String src = new String(java.nio.file.Files.readAllBytes(srcFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("编辑保存后必须作废这一轮的小睡", src.contains("ScheduleManager.dropSnooze(activity, target.id)"));
        assertTrue("而且只在时间/规则真的变了时才清——只换铃声不该掐掉正在等的小睡",
                src.contains("oldHour != target.hour"));
    }

    /**
     * v0.91.9：删掉一条闹钟，它没兑现的小睡必须跟着作废。
     *
     * <p>走的是真实路径：首页 {@code removeAlarm} → {@code applyAlarmChange}。
     * 两头都清不到：还有别的闹钟开着时走 {@code rescheduleAll}，而它刻意不碰 SNOOZE 段；
     * 一条都不剩时走 {@code cancelAllIncludingTransient}，可它遍历的是「列表里还在的闹钟」，
     * 被删的那条已经不在表里。于是 5 分钟后那一趟照样触发——闹钟已经不存在，
     * AlarmService 查不到它的铃声，回落到默认铃声，用户听到的是「不知道哪来的闹钟」。
     */
    @Test
    public void removeAlarm_dropsPendingSnooze() {
        // 留一条别的闹钟开着 → 走 rescheduleAll 分支（不是「全清」）
        Prefs.setAlarms(ctx(), java.util.Arrays.asList(
                new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true),
                new Alarm(2, 13, 30, "午休", Alarm.Mode.DAILY, true)));

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        LocalDate today = LocalDate.now();
        ScheduleManager.snooze(ctx(), 1, "起床");
        assertTrue("前提：小睡排上了", ScheduleManager.isSnoozeRegistered(ctx(), 1, today));

        a.removeAlarm(new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true));

        assertFalse("删掉的闹钟不该还能在 5 分钟后响一次",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, today));
        assertFalse("跨零点的小睡落在明天，也要一起清",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, today.plusDays(1)));
        assertTrue("另一条闹钟的小睡不受影响（这条本来就没排，只是确认没误伤到 id）",
                !ScheduleManager.isSnoozeRegistered(ctx(), 2, today));
    }

    /**
     * v0.91.9：停用（关掉开关）同样要作废小睡——「这条以后不响了」。
     *
     * <p>toggleAlarm 是 private，且要点到行里的开关才能触发；这里钉的是
     * 「那条分支上有没有这个调用」，与上面 editorSave 的写法一致。
     */
    @Test
    public void disableAlarm_callsDropSnooze() throws Exception {
        String src = readSource("MainActivity.java");
        assertTrue("停用闹钟时必须作废它待执行的小睡，否则关掉之后还会响一次",
                src.contains("if (!checked) ScheduleManager.dropSnooze(this, x.id);"));
        assertTrue("删除路径同理", src.contains("ScheduleManager.dropSnooze(this, a.id);"));
    }

    /**
     * v0.91.9：跨零点的小睡要把「起始日」一路带下去，否则次数会在零点归零。
     *
     * <p>23:58 响 → 点小睡排到 00:03 → 00:03 再响时 URI 里的日期已经是新的一天，
     * 若照它记账，次数从 0 开始，「还能睡 3 次」直接满血。
     */
    @Test
    public void snoozeCarriesOriginDate() {
        LocalDate origin = LocalDate.of(2026, 10, 31);
        ScheduleManager.snooze(ctx(), 1, "起床", origin);

        // 从系统里把刚注册的那条捞出来，读它真正携带的 extra——
        // 光断言源码里有这行字不算数，PendingIntent 可能压根没带上
        android.app.AlarmManager am = (android.app.AlarmManager)
                ctx().getSystemService(Context.ALARM_SERVICE);
        org.robolectric.shadows.ShadowAlarmManager sam = shadowOf(am);
        boolean found = false;
        for (org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm sa : sam.getScheduledAlarms()) {
            if (sa.operation == null) continue;
            Intent i = shadowOf(sa.operation).getSavedIntent();
            if (i == null || i.getComponent() == null) continue;
            if (!AlarmReceiver.class.getName().equals(i.getComponent().getClassName())) continue;
            found = true;
            assertEquals("小睡 Intent 必须带上起始日，否则零点之后次数会归零",
                    origin.toString(), i.getStringExtra(AlarmReceiver.EXTRA_SNOOZE_ORIGIN));
        }
        assertTrue("前提：小睡已经注册进系统", found);
    }

    /** 读一份生产源码（仓库根向上找）。 */
    private static String readSource(String name) throws Exception {
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        while (dir != null && !new java.io.File(dir, "app/src/main").isDirectory()) {
            dir = dir.getParentFile();
        }
        assertTrue("找不到仓库根目录", dir != null);
        java.io.File f = new java.io.File(dir,
                "app/src/main/java/io/github/iosephuschen/weekdayalarm/" + name);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
