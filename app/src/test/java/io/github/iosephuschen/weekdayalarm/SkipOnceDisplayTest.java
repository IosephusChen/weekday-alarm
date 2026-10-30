package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 「这次不响」之后，界面上的数字必须跟着变（v0.91.4）。
 *
 * <p>跳过标记（{@code skipOnce}）在 v0.87.5 就进了排程：{@code rescheduleAll} 会认它、
 * 不排那一天的实例。但**纯计算的那几条路没认**——首页倒计时、桌面小组件、设置页
 * 「已排 N 次」都还按「理论上该响」算，于是：
 *
 * <ul>
 *   <li>用户点了「这次不响」，首页照旧写着「明天 07:30」，他会以为按钮没生效；</li>
 *   <li>第二天真没响，他又会怀疑 App 漏了一天；</li>
 *   <li>设置页那个「本次共排 N 次」纹丝不动，和系统里实际登记的差一个。</li>
 * </ul>
 *
 * <p>这类错都不会崩、不会报错，只是「界面说的」和「它做的事」对不上。所以钉在这里。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SkipOnceDisplayTest {

    /** 固定的一天当「现在」，避免测试随真实时钟漂（07:30 前后跑出不同结果）。 */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 3, 2, 0, 0);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    /** 每天 07:30 一条。DAILY 不看周休制度，测试不依赖那天是星期几。 */
    private void dailyAlarm() {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true);
        Prefs.setAlarms(ctx(), Collections.singletonList(a));
    }

    @Test
    public void nextRing_skipsTheDayMarkedSilent() {
        dailyAlarm();
        LocalDate today = FROM.toLocalDate();
        ScheduleManager.NextAlarm before = ScheduleManager.nextRing(ctx(), FROM);
        assertNotNull(before);
        assertEquals("前提：没点跳过时，下一次就是今天 07:30", today, before.when.toLocalDate());

        Prefs.addSkipOnce(ctx(), 1, today);

        ScheduleManager.NextAlarm after = ScheduleManager.nextRing(ctx(), FROM);
        assertNotNull(after);
        assertEquals("点过「这次不响」之后，首页不该还写着今天会响",
                today.plusDays(1), after.when.toLocalDate());
    }

    /** 只跳过一天，后天要正常回来——标记是「这一次」，不是「这个闹钟」。 */
    @Test
    public void nextRing_comesBackTheDayAfter() {
        dailyAlarm();
        LocalDate today = FROM.toLocalDate();
        Prefs.addSkipOnce(ctx(), 1, today);

        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(ctx(), FROM.plusDays(1));
        assertNotNull(next);
        assertEquals("第二天照常响", today.plusDays(1), next.when.toLocalDate());
    }

    /** 别条闹钟不受影响：跳过按「闹钟 + 日期」记账，不是全局静音。 */
    @Test
    public void nextRing_otherAlarmUnaffected() {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true);
        Alarm b = new Alarm(2, 8, 0, "出门", Alarm.Mode.DAILY, true);
        java.util.List<Alarm> list = new java.util.ArrayList<>();
        list.add(a);
        list.add(b);
        Prefs.setAlarms(ctx(), list);

        LocalDate today = FROM.toLocalDate();
        Prefs.addSkipOnce(ctx(), 1, today);

        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(ctx(), FROM);
        assertNotNull(next);
        assertEquals("跳过 07:30，下一次应是同一天 08:00", 2, next.alarmId);
        assertEquals(today, next.when.toLocalDate());
    }

    /**
     * 设置页「本次共排 N 次」也要把跳过的扣掉。
     *
     * <p>拿「明天」做标记而不是今天：今天 7:30 可能已经过点、本来就不计数，
     * 那样断言会因为跑测试的钟点不同而时红时绿。
     */
    @Test
    public void countScheduled_dropsSkippedInstance() {
        dailyAlarm();
        int before = ScheduleManager.countScheduled(ctx());
        assertTrue("前提：90 天里至少排到一次", before > 0);

        Prefs.addSkipOnce(ctx(), 1, LocalDate.now().plusDays(1));

        assertEquals("跳过一天就该少一次，否则这个数字是骗人的",
                before - 1, ScheduleManager.countScheduled(ctx()));
    }

    /**
     * 首页读的必须是「认跳过」的那条路（v0.91.6）。
     *
     * <p>0.91.4 修了 {@link ScheduleManager#nextRing} 本身，可首页在
     * {@code MainActivity.refreshStatus} 里调的是另一个**不带 skipOnce 的重载**，
     * 那个重载内部塞空集。真机上的表现正是用户报的：点完「这次不响」，通知没了、
     * 设置页的统计少了一个，首页倒计时和小组件却照旧指着今天。
     *
     * <p>为什么上面几条测试没拦住：它们都直接调带 skipOnce 的版本，等于测了一条
     * UI 从不走的路。所以这条必须从界面读——把 Activity 真起起来，看那行字。
     *
     * <p>用「明天 09:00」做素材而不是「今天某点」：跑测试的钟点不确定，取今天
     * 可能已经过点。换个日子，断言也变成「明天」→「后天」，跟当前时间无关。
     */
    @Test
    public void homeScreen_afterSkip_pointsToTheDayAfter() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Alarm a = new Alarm(1, 9, 0, "起床", Alarm.Mode.DAILY, true);
        Prefs.setAlarms(ctx(), Collections.singletonList(a));

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();

        String before = ((TextView) act.findViewById(R.id.tv_today_sub)).getText().toString();
        assertTrue("前提：没跳过时首页该写「明天 09:00」，实际是「" + before + "」",
                before.contains("明天 09:00"));

        Prefs.addSkipOnce(ctx(), 1, tomorrow);
        c.pause().resume();   // 回到前台，走的是真实刷新路径

        String after = ((TextView) act.findViewById(R.id.tv_today_sub)).getText().toString();
        assertFalse("点完「这次不响」，首页不能还写着明天那一趟：「" + after + "」",
                after.contains("明天 09:00"));
        assertTrue("跳过明天，下次就该是后天：「" + after + "」", after.contains("后天 09:00"));
    }
}
