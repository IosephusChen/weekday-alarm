package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「接下来 N 次响铃」的纯计算（v1.29.0）。
 *
 * <p>这个方法的难点不在「取前三条」，而在三件事：同一天多条要按时刻排、请假要跳过、
 * 一次性闹钟只出现一次。三条都容易在改排班逻辑时被带坏，所以分开钉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class NextRingsTest {

    /** 双休：周六周日休息，不需要基准日。 */
    private static final WeekSchedule.Rule DOUBLE =
            new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false);
    private static final Set<LocalDate> NONE = Collections.emptySet();

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 5, 0);   // 周三 05:00

    private List<ScheduleManager.NextAlarm> ask(List<Alarm> alarms, Set<LocalDate> vacations,
                                                int limit) {
        return ScheduleManager.nextRings(alarms, DOUBLE, vacations, NONE, NOW, 30, limit);
    }

    /** 同一天多条：必须按时刻升序，而不是按闹钟列表顺序。 */
    @Test
    public void sameDay_sortedByTime() {
        List<Alarm> alarms = Arrays.asList(
                new Alarm(1, 8, 0, "晚的", Alarm.Mode.DAILY, true),
                new Alarm(2, 6, 30, "早的", Alarm.Mode.DAILY, true));
        List<ScheduleManager.NextAlarm> got = ask(alarms, NONE, 3);
        assertEquals(3, got.size());
        assertEquals("06:30", time(got.get(0)));
        assertEquals("08:00", time(got.get(1)));
        assertEquals("第二天 06:30", "06:30", time(got.get(2)));
        assertTrue("第二条应已跨到次日",
                got.get(2).when.toLocalDate().isAfter(got.get(1).when.toLocalDate()));
    }

    /** limit 生效：要三次就只给三次，不多算也不重复。 */
    @Test
    public void limit_isRespected() {
        List<Alarm> alarms = Collections.singletonList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        assertEquals(1, ask(alarms, NONE, 1).size());
        assertEquals(3, ask(alarms, NONE, 3).size());
        assertEquals("limit 0 视为不要", 0, ask(alarms, NONE, 0).size());
        // 三次分别在今天 / 明天 / 后天，不该出现同一天的重复
        List<ScheduleManager.NextAlarm> got = ask(alarms, NONE, 3);
        assertEquals(3, got.stream().map(n -> n.when.toLocalDate()).distinct().count());
    }

    /** 请假当天整天的闹钟都跳过（一次性闹钟例外，见下一条）。 */
    @Test
    public void vacation_dayIsSkipped() {
        LocalDate today = NOW.toLocalDate();
        List<Alarm> alarms = Collections.singletonList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        List<ScheduleManager.NextAlarm> got = ask(alarms, new HashSet<>(Arrays.asList(today)), 3);
        assertEquals(3, got.size());
        assertEquals("今天请假，第一条应是明天", today.plusDays(1), got.get(0).when.toLocalDate());
    }

    /** 一次性闹钟只在它那一天出现一次，不会第二天又冒出来。 */
    @Test
    public void onceAlarm_appearsExactlyOnce() {
        LocalDate tomorrow = NOW.toLocalDate().plusDays(1);
        List<Alarm> alarms = Collections.singletonList(
                new Alarm(1, 6, 30, "赶车", Alarm.Mode.ONCE, true, tomorrow.toString()));
        List<ScheduleManager.NextAlarm> got = ask(alarms, NONE, 3);
        assertEquals("只有一次", 1, got.size());
        assertEquals(tomorrow, got.get(0).when.toLocalDate());
    }

    /** 关掉的闹钟一条都不该出现——包括「一次性」那种已自动关闭的。 */
    @Test
    public void disabledAlarms_neverListed() {
        List<Alarm> alarms = new ArrayList<>();
        alarms.add(new Alarm(1, 7, 0, "关着的", Alarm.Mode.DAILY, false));
        assertTrue(ask(alarms, NONE, 3).isEmpty());
    }

    /** 空列表不炸、不返回 null——UI 直接遍历，返回 null 会在弹窗里崩。 */
    @Test
    public void emptyList_returnsEmptyNotNull() {
        List<ScheduleManager.NextAlarm> got = ask(new ArrayList<>(), NONE, 3);
        assertEquals(0, got.size());
    }

    // ---------- 小组件复用主界面算好的结果 ----------

    /**
     * 传进来的 next / count 必须被原样用上，而不是内部再算一遍。
     *
     * <p>这里刻意传一个「和真实数据不一致」的 count：如果实现偷偷重算，
     * 副标题会写成真实条数，断言就会红。这正是要防的回归。
     */
    @Test
    public void widgetModel_usesProvidedValues() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Prefs.setEnabled(ctx, true);
        Prefs.setAlarms(ctx, Collections.singletonList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true)));

        ScheduleManager.NextAlarm fake = new ScheduleManager.NextAlarm(
                LocalDateTime.of(2026, 10, 22, 7, 0), "起床", 1);
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx, fake, 42);
        assertTrue("标题应写传入的时间，实际：" + m.title, m.title.contains("10月22日"));
        assertTrue("副标题应写传入的条数，实际：" + m.subtitle, m.subtitle.contains("42"));
    }

    /** 总开关关着时，即使传了 next 也必须是「尚未开启」——状态优先于数据。 */
    @Test
    public void widgetModel_masterOff_winsOverProvidedNext() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Prefs.setEnabled(ctx, false);
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx,
                new ScheduleManager.NextAlarm(LocalDateTime.now().plusHours(1), "起床", 1), 9);
        assertEquals("尚未开启", m.title);
    }

    private static String time(ScheduleManager.NextAlarm n) {
        return String.format(java.util.Locale.US, "%02d:%02d",
                n.when.getHour(), n.when.getMinute());
    }
}
