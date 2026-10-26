package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v0.28.0：一周开始日（周一 / 周日）+ 今日状态里的「下一个闹钟」。
 *
 * v0.30.0：首页改成「距下次响铃」倒计时，判断口径统一到 ScheduleManager.nextRing，
 * 这里一并覆盖跨天、休息日的「每天」闹钟、请假与加班。
 *
 * 这两件事都在界面上，但真正的错法都在计算里：
 * 起始日算错 → 整个月历错位一天；下一个闹钟算错 → 下午还有闹钟却提示「已响过」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WeekStartTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
    }

    @Before
    public void setUp() {
        sp().edit().clear().commit();
    }

    @Test
    public void weekStart_defaultsToMonday_andPersistsSunday() {
        assertEquals(Prefs.WEEK_START_MONDAY, Prefs.getWeekStart(ctx()));
        assertFalse(Prefs.isSundayFirst(ctx()));

        Prefs.setWeekStart(ctx(), Prefs.WEEK_START_SUNDAY);
        assertEquals(Prefs.WEEK_START_SUNDAY, Prefs.getWeekStart(ctx()));
        assertTrue(Prefs.isSundayFirst(ctx()));

        Prefs.setWeekStart(ctx(), Prefs.WEEK_START_MONDAY);
        assertFalse(Prefs.isSundayFirst(ctx()));
    }

    /** 脏数据（手改配置成 7）按周一起始，和默认值一致。 */
    @Test
    public void weekStart_outOfRange_fallsBackToMonday() {
        sp().edit().putInt(Prefs.KEY_WEEK_START, 7).commit();
        assertEquals(Prefs.WEEK_START_MONDAY, Prefs.getWeekStart(ctx()));
    }

    /** 2026-10-01 是周四：周一起始回退 3 天，周日起始回退 4 天。 */
    @Test
    public void startOfWeek_respectsFirstDay() {
        LocalDate thu = LocalDate.of(2026, 10, 1);
        assertEquals(LocalDate.of(2026, 9, 28), WeekSchedule.startOfWeek(thu, false));
        assertEquals(LocalDate.of(2026, 9, 27), WeekSchedule.startOfWeek(thu, true));

        // 周日当天：周一起始要回退到上周一，周日起始就是当天
        LocalDate sun = LocalDate.of(2026, 10, 4);
        assertEquals(LocalDate.of(2026, 9, 28), WeekSchedule.startOfWeek(sun, false));
        assertEquals(sun, WeekSchedule.startOfWeek(sun, true));

        // 周一当天：周一起始就是当天；周日起始则属于前一天（周日）那一周
        LocalDate mon = LocalDate.of(2026, 9, 28);
        assertEquals(mon, WeekSchedule.startOfWeek(mon, false));
        assertEquals(LocalDate.of(2026, 9, 27), WeekSchedule.startOfWeek(mon, true));
    }

    @Test
    public void weekLetters_rotateWithFirstDay() {
        String[] mon = WeekSchedule.weekLetters(false);
        String[] sun = WeekSchedule.weekLetters(true);
        assertEquals(7, mon.length);
        assertEquals("一", mon[0]);
        assertEquals("日", mon[6]);
        assertEquals("日", sun[0]);
        assertEquals("六", sun[6]);
        // 同一星期在两套顺序里的位置差一天（模 7）
        for (int i = 0; i < 7; i++) {
            assertEquals(mon[(i + 6) % 7], sun[i]);
        }
    }

    /** 日历表头跟着设置换：周日起始时第一列必须是「日」。 */
    @Test
    public void calendarHeader_followsSetting() {
        Prefs.setWeekStart(ctx(), Prefs.WEEK_START_SUNDAY);
        Prefs.setOnboardDone(ctx(), true);   // v0.48.1：日历在首页，别让新手引导页插进来
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        assertEquals("日", ((TextView) a.findViewById(R.id.cal_dow_0)).getText().toString());
        assertEquals("六", ((TextView) a.findViewById(R.id.cal_dow_6)).getText().toString());

        sp().edit().putInt(Prefs.KEY_WEEK_START, Prefs.WEEK_START_MONDAY).commit();
        MainActivity b = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        assertEquals("一", ((TextView) b.findViewById(R.id.cal_dow_0)).getText().toString());
    }

    /** 小组件同样按设置排列：周日起始时今天应落在「星期几」对应的那一列。 */
    @Test
    public void widget_followsWeekStart() {
        Prefs.setWeekStart(ctx(), Prefs.WEEK_START_SUNDAY);
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        assertEquals("日", m.days.get(0).dayLetter);
        assertEquals("六", m.days.get(6).dayLetter);

        int idx = LocalDate.now().getDayOfWeek().getValue() % 7;   // 周日=0，周一=1 …
        assertTrue("今天应高亮在第 " + idx + " 列", m.days.get(idx).today);
    }

    // ================= 下一次响铃（v0.30.0 起首页倒计时与「下次」共用）=================

    private static List<Alarm> alarms() {
        return Arrays.asList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true),
                new Alarm(2, 13, 40, "午休", Alarm.Mode.WORKDAY, true));
    }

    private static final WeekSchedule.Rule DOUBLE_REST =
            new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false);

    private static ScheduleManager.NextAlarm ring(List<Alarm> list,
                                                  WeekSchedule.Rule rule,
                                                  LocalDateTime from) {
        return ScheduleManager.nextRing(list, rule, Collections.emptySet(),
                Collections.emptySet(), from, 30);
    }

    /** 早上那趟响过之后，下午还有午休，下一次必须是 13:40（v0.28.0 修的老问题）。 */
    @Test
    public void nextRing_afterMorningAlarm_returnsAfternoonOne() {
        ScheduleManager.NextAlarm next = ring(alarms(), DOUBLE_REST,
                LocalDateTime.of(2026, 10, 20, 12, 8));
        assertNotNull("下午还有午休闹钟，不该说「今天已响过」", next);
        assertEquals(13, next.when.getHour());
        assertEquals(40, next.when.getMinute());
        assertEquals("午休", next.label);
    }

    /** 正好到点算「已响过」，与调度口径一致（闹钟在那一分钟触发）。 */
    @Test
    public void nextRing_exactlyOnTime_countsAsPassed() {
        ScheduleManager.NextAlarm next = ring(alarms(), DOUBLE_REST,
                LocalDateTime.of(2026, 10, 20, 7, 0));
        assertEquals(13, next.when.getHour());
    }

    /** 今天的都过完：应该跨到明天早上，而不是返回 null 让首页写「已响完」。 */
    @Test
    public void nextRing_afterAllAlarms_rollsToTomorrow() {
        ScheduleManager.NextAlarm next = ring(alarms(), DOUBLE_REST,
                LocalDateTime.of(2026, 10, 20, 14, 0));
        assertEquals(LocalDate.of(2026, 10, 21), next.when.toLocalDate());
        assertEquals(7, next.when.getHour());
    }

    @Test
    public void nextRing_noAlarm_returnsNull() {
        assertNull(ring(Collections.emptyList(), DOUBLE_REST,
                LocalDateTime.of(2026, 10, 20, 6, 0)));
    }

    @Test
    public void nextRing_disabledAlarm_isIgnored() {
        List<Alarm> list = Collections.singletonList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, false));
        assertNull(ring(list, DOUBLE_REST, LocalDateTime.of(2026, 10, 20, 6, 0)));
    }

    /**
     * v0.30.0 修：周六是休息日，但「每天」闹钟照样响。
     * 旧首页只算「今天算上班」的日子，于是这种日子会写「休息日，闹钟不响」。
     */
    @Test
    public void nextRing_onRestDay_stillReturnsDailyAlarm() {
        List<Alarm> list = Collections.singletonList(
                new Alarm(1, 9, 0, "懒觉", Alarm.Mode.DAILY, true));
        ScheduleManager.NextAlarm next = ring(list, DOUBLE_REST,
                LocalDateTime.of(2026, 10, 24, 8, 0));   // 2026-10-24 是周六
        assertNotNull("休息日的「每天」闹钟也会响，不能返回 null", next);
        assertEquals(LocalDate.of(2026, 10, 24), next.when.toLocalDate());
        assertEquals(9, next.when.getHour());
    }

    /** 请假日整天的闹钟跳过，下一次要越过它。 */
    @Test
    public void nextRing_skipsVacationDay() {
        Set<LocalDate> vac = new HashSet<>();
        vac.add(LocalDate.of(2026, 10, 21));
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(alarms(), DOUBLE_REST,
                vac, Collections.emptySet(), LocalDateTime.of(2026, 10, 20, 14, 0), 30);
        assertEquals(LocalDate.of(2026, 10, 22), next.when.toLocalDate());
    }

    /** 休息日标记加班：「仅上班日」闹钟当天照响，下一次就是今天。 */
    @Test
    public void nextRing_overtimeRestDay_ringsWorkdayAlarm() {
        Set<LocalDate> ot = new HashSet<>();
        ot.add(LocalDate.of(2026, 10, 24));
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(alarms(), DOUBLE_REST,
                Collections.emptySet(), ot, LocalDateTime.of(2026, 10, 24, 6, 0), 30);
        assertEquals(LocalDate.of(2026, 10, 24), next.when.toLocalDate());
        assertEquals(7, next.when.getHour());
    }

    // ================= 倒计时文案 =================

    @Test
    public void countdown_underOneDay_showsHms() {
        assertEquals("00:00:00", MainActivity.fmtCountdown(0));
        assertEquals("00:00:59", MainActivity.fmtCountdown(59));
        assertEquals("00:47:12", MainActivity.fmtCountdown(47 * 60 + 12));
        assertEquals("02:13:45", MainActivity.fmtCountdown(2 * 3600 + 13 * 60 + 45));
        assertEquals("23:59:59", MainActivity.fmtCountdown(86399));
    }

    /** 超过一天改写成「N 天 时:分」：每秒跳秒没有意义，还会让人以为马上要响。 */
    @Test
    public void countdown_overOneDay_showsDays() {
        assertEquals("1 天 00:00", MainActivity.fmtCountdown(86400));
        assertEquals("3 天 04:05", MainActivity.fmtCountdown(3 * 86400 + 4 * 3600 + 5 * 60 + 59));
    }
}
