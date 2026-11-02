package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

/**
 * 闹钟按班表逐日时间响（v1.6.0）。
 *
 * <p><b>要解决的问题</b>：轮班的人早班 7:30、晚班 19:30，以前只能按班型建两条闹钟，
 * 每周自己关一条。班表里明明填了每天的时间，闹钟却读不到——{@code cycleTimeOf}
 * 早就在，只是产品代码零调用方，填的 07:30 存得下但不生效。
 *
 * <p><b>三条不能破的底线</b>：
 * <ul>
 *   <li>不开这个开关的闹钟，行为与 v1.5.0 逐字一致；老备份读进来一律视为不开。</li>
 *   <li>班表那天取不到时间（休息、没填、非轮班）时退回自己的 hour/minute——
 *       宁可响在旧时刻，也不能因为班表漏了一天就整天不响。</li>
 *   <li>这条只看「几点响」，「哪天响」仍由 mode 决定，两件事正交。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FollowScheduleTest {

    /** 第 1 天。挑周一只是为了让「上 2 休 2」跨周末，读起来直观。 */
    private static final LocalDate START = LocalDate.of(2026, 11, 2);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** 4 天一轮：上 2 休 2，早班 08:00、晚班 20:00。 */
    private static WeekSchedule.Rule cycleRule() {
        return WeekSchedule.Rule.cycle(4, 0b0011L, START,
                new int[]{8 * 60, 20 * 60, -1, -1}, false);
    }

    private static Alarm alarm() {
        return new Alarm(1, 7, 30, "上班", Alarm.Mode.WORKDAY, true);
    }

    // ================= 关闭时与旧版一致 =================

    @Test
    public void off_usesOwnTime() {
        Alarm a = alarm();
        assertEquals(LocalTime.of(7, 30), a.timeOn(START, cycleRule()));
        assertEquals(LocalTime.of(7, 30), a.timeOn(START.plusDays(1), cycleRule()));
    }

    // ================= 打开时逐日读班表 =================

    @Test
    public void on_readsTimeOfThatDay() {
        Alarm a = alarm();
        a.followSchedule = true;
        WeekSchedule.Rule g = cycleRule();
        assertEquals(LocalTime.of(8, 0), a.timeOn(START, g));
        assertEquals(LocalTime.of(20, 0), a.timeOn(START.plusDays(1), g));
    }

    @Test
    public void on_wrapsAroundCycle() {
        Alarm a = alarm();
        a.followSchedule = true;
        // 第 5 天回到循环第 1 天：8:00，不是「第 5 个时间」
        assertEquals(LocalTime.of(8, 0), a.timeOn(START.plusDays(4), cycleRule()));
    }

    /** 起点之前的日期也要算对：Math.floorMod 保证下标非负。 */
    @Test
    public void on_handlesDaysBeforeStart() {
        Alarm a = alarm();
        a.followSchedule = true;
        // START 前 2 天 = 循环第 3 天（休），回退到 7:30
        assertEquals(LocalTime.of(7, 30), a.timeOn(START.minusDays(2), cycleRule()));
        // START 前 1 天 = 循环第 4 天（休），同样回退
        assertEquals(LocalTime.of(7, 30), a.timeOn(START.minusDays(1), cycleRule()));
    }

    // ================= 取不到时间就回退 =================

    @Test
    public void restDay_fallsBackToOwnTime() {
        Alarm a = alarm();
        a.followSchedule = true;
        assertEquals(LocalTime.of(7, 30), a.timeOn(START.plusDays(2), cycleRule()));
    }

    @Test
    public void workdayWithoutTime_fallsBack() {
        Alarm a = alarm();
        a.followSchedule = true;
        // 上班但那天没填时间（-1）：不能因此不响
        WeekSchedule.Rule g = WeekSchedule.Rule.cycle(2, 0b11L, START,
                new int[]{-1, 9 * 60}, false);
        assertEquals(LocalTime.of(7, 30), a.timeOn(START, g));
        assertEquals(LocalTime.of(9, 0), a.timeOn(START.plusDays(1), g));
    }

    @Test
    public void nonCycleRule_fallsBack() {
        Alarm a = alarm();
        a.followSchedule = true;
        // 双休的班表里没有「每天不同的时间」，开关开着也只能按自己的时间响
        WeekSchedule.Rule g = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, START, false);
        assertEquals(LocalTime.of(7, 30), a.timeOn(START, g));
    }

    @Test
    public void ownRule_fallsBack() {
        Alarm a = new Alarm(1, 6, 45, "吃药", Alarm.Mode.CUSTOM, true);
        a.applyOwnRule(WeekSchedule.Rule.cycle(7, 0b0011111L, START, null, true));
        a.followSchedule = true;
        // 自带班表只存了上不上班，没有逐日时间——读全局那张就会串到别人的作息
        assertEquals(LocalTime.of(6, 45), a.timeOn(START, cycleRule()));
    }

    // ================= 备份兼容 =================

    @Test
    public void json_roundTripsFlag() {
        Alarm a = alarm();
        a.followSchedule = true;

        List<Alarm> back = Alarm.fromJson(Alarm.toJson(Collections.singletonList(a)));
        assertEquals(1, back.size());
        assertTrue(back.get(0).followSchedule);
    }

    @Test
    public void json_omitsDefaultValue() {
        Alarm a = alarm();
        // 默认值不落盘：老用户的备份文件不该因为升级多出一堆 false
        assertFalse(Alarm.toJson(Collections.singletonList(a)).contains("followSchedule"));
    }

    @Test
    public void legacyBackup_readsAsOff() throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("id", 3);
        o.put("hour", 7);
        o.put("minute", 30);
        o.put("label", "上班");
        o.put("mode", Alarm.Mode.WORKDAY.ordinal());
        o.put("enabled", true);
        arr.put(o);

        List<Alarm> back = Alarm.fromJson(arr.toString());
        assertEquals(1, back.size());
        // 新增功能不能改变已有闹钟的行为
        assertFalse(back.get(0).followSchedule);
        assertEquals(LocalTime.of(7, 30), back.get(0).timeOn(START, cycleRule()));
    }

    // ================= 编辑页那行的显隐与文案 =================

    @Test
    public void canFollow_needsCycleAndAtLeastOneTime() {
        assertTrue(AlarmEditor.canFollow(cycleRule()));
        // 轮班但没填过时间
        assertFalse(AlarmEditor.canFollow(WeekSchedule.Rule.cycle(4, 0b0011L, START, null, false)));
        // 填了，但全是 -1
        assertFalse(AlarmEditor.canFollow(WeekSchedule.Rule.cycle(2, 0b11L, START,
                new int[]{-1, -1}, false)));
        // 不是轮班
        assertFalse(AlarmEditor.canFollow(new WeekSchedule.Rule(
                WeekSchedule.Mode.DOUBLE_REST, START, false)));
        assertFalse(AlarmEditor.canFollow(null));
    }

    @Test
    public void summary_whenOff_talksAboutWhatItBuys() {
        String s = AlarmEditor.followSummary(cycleRule(), false, 7, 30);
        assertTrue(s, s.contains("班表"));
        assertTrue(s, !s.contains("未开启"));
    }

    @Test
    public void summary_whenOn_showsTodayAndTomorrow() {
        // 系统日期不固定，但班表起点固定在 START，文案只断言格式与两天都在
        String s = AlarmEditor.followSummary(cycleRule(), true, 7, 30);
        assertTrue(s, s.contains("今天"));
        assertTrue(s, s.contains("明天"));
    }

    @Test
    public void summary_withoutTime_explainsFallback() {
        WeekSchedule.Rule g = WeekSchedule.Rule.cycle(4, 0b0011L, START, null, false);
        String s = AlarmEditor.followSummary(g, false, 7, 30);
        assertTrue(s, s.contains("07:30"));
        assertTrue(s, s.contains("班表"));
    }
}
