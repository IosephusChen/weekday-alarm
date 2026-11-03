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
 * 闹钟按班表逐日时间响（v1.6.0 起，v1.7.0 收进模式）。
 *
 * <p><b>要解决的问题</b>：轮班的人早班 7:30、晚班 19:30，以前只能按班型建两条闹钟，
 * 每周自己关一条。班表里明明填了每天的时间，闹钟却读不到——{@code cycleTimeOf}
 * 早就在，只是产品代码零调用方，填的 07:30 存得下但不生效。
 *
 * <p><b>v1.7.0 改了什么</b>：v1.6.0 靠一个独立的 {@code followSchedule} 开关决定
 * 「几点响」，结果编辑页上出现两个都叫「跟班表」的入口（一个管哪天、一个管几点）。
 * 现在这件事并进 {@link Alarm.Mode#WORKDAY}：选「跟随我的班表」就等于哪天、几点
 * 都听班表的，字段与开关一起删掉。
 *
 * <p><b>四条不能破的底线</b>：
 * <ul>
 *   <li>只有 WORKDAY 读班表时刻。「每天」「仅休息日」「自己排一周」用自己那个固定
 *       时刻——那几张卡回答的是「多久响一次」，跟班次时间无关。</li>
 *   <li>班表那天取不到时间（休息、没填、非轮班）时退回自己的 hour/minute——
 *       宁可响在旧时刻，也不能因为班表漏了一天就整天不响。</li>
 *   <li>双休、单休、大小周的班表里没有逐日时刻，那些用户永远走回退分支，
 *       行为与 v1.5.0 逐字一致。</li>
 *   <li>升级不能弄丢老数据：v1.6.0 写下的 {@code followSchedule} 键读进来当做没看见，
 *       一切按 mode 重新判定。</li>
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

    private static Alarm workday() {
        return new Alarm(1, 7, 30, "上班", Alarm.Mode.WORKDAY, true);
    }

    // ================= 跟随我的班表：逐日读班表 =================

    @Test
    public void workday_readsTimeOfThatDay() {
        WeekSchedule.Rule g = cycleRule();
        assertEquals(LocalTime.of(8, 0), workday().timeOn(START, g));
        assertEquals(LocalTime.of(20, 0), workday().timeOn(START.plusDays(1), g));
    }

    @Test
    public void workday_wrapsAroundCycle() {
        // 第 5 天回到循环第 1 天：8:00，不是「第 5 个时间」
        assertEquals(LocalTime.of(8, 0), workday().timeOn(START.plusDays(4), cycleRule()));
    }

    /** 起点之前的日期也要算对：Math.floorMod 保证下标非负。 */
    @Test
    public void workday_handlesDaysBeforeStart() {
        // START 前 2 天 = 循环第 3 天（休），回退到 7:30
        assertEquals(LocalTime.of(7, 30), workday().timeOn(START.minusDays(2), cycleRule()));
        // START 前 1 天 = 循环第 4 天（休），同样回退
        assertEquals(LocalTime.of(7, 30), workday().timeOn(START.minusDays(1), cycleRule()));
    }

    // ================= 取不到时间就回退 =================

    @Test
    public void restDay_fallsBackToOwnTime() {
        assertEquals(LocalTime.of(7, 30), workday().timeOn(START.plusDays(2), cycleRule()));
    }

    @Test
    public void workdayWithoutTime_fallsBack() {
        // 上班但那天没填时间（-1）：不能因此不响
        WeekSchedule.Rule g = WeekSchedule.Rule.cycle(2, 0b11L, START,
                new int[]{-1, 9 * 60}, false);
        assertEquals(LocalTime.of(7, 30), workday().timeOn(START, g));
        assertEquals(LocalTime.of(9, 0), workday().timeOn(START.plusDays(1), g));
    }

    @Test
    public void nonCycleRule_fallsBack() {
        // 双休的班表里没有「每天不同的时间」，只能按自己的时间响
        WeekSchedule.Rule g = new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, START, false);
        assertEquals(LocalTime.of(7, 30), workday().timeOn(START, g));
    }

    // ================= 其他模式不吃班表时刻（v1.7.0 的关键边界）=================

    /**
     * 「每天」是「每天都响」，不是「每天都按班次响」。
     *
     * <p>v1.6.0 时这两件事可以同时成立（模式选「每天」、再拨开那个开关），
     * 现在开关没了，只剩一种解释——否则刚删掉的那个歧义会以另一种形式回来。
     */
    @Test
    public void daily_usesOwnTime() {
        Alarm a = new Alarm(1, 6, 45, "吃药", Alarm.Mode.DAILY, true);
        assertEquals(LocalTime.of(6, 45), a.timeOn(START, cycleRule()));
        assertEquals(LocalTime.of(6, 45), a.timeOn(START.plusDays(1), cycleRule()));
    }

    /** 「仅休息日」那天班表根本没时刻，读它必然空转——这条就是 6.1 里那条 S1。 */
    @Test
    public void weekend_usesOwnTime() {
        Alarm a = new Alarm(1, 21, 0, "周末", Alarm.Mode.WEEKEND, true);
        assertEquals(LocalTime.of(21, 0), a.timeOn(START.plusDays(2), cycleRule()));
    }

    @Test
    public void ownRule_usesOwnTime() {
        Alarm a = new Alarm(1, 6, 45, "吃药", Alarm.Mode.CUSTOM, true);
        a.applyOwnRule(WeekSchedule.Rule.cycle(7, 0b0011111L, START, null, true));
        // 自带班表只存了上不上班，没有逐日时间——读全局那张就会串到别人的作息
        assertEquals(LocalTime.of(6, 45), a.timeOn(START, cycleRule()));
    }

    // ================= 编辑页：谁来读班表 =================

    @Test
    public void follows_onlyForWorkdayWithTimes() {
        WeekSchedule.Rule g = cycleRule();
        assertTrue(AlarmEditor.follows(Alarm.Mode.WORKDAY, g));
        // 其他模式一律不读，哪怕班表里有时刻
        assertFalse(AlarmEditor.follows(Alarm.Mode.DAILY, g));
        assertFalse(AlarmEditor.follows(Alarm.Mode.WEEKEND, g));
        assertFalse(AlarmEditor.follows(Alarm.Mode.ONCE, g));
        assertFalse(AlarmEditor.follows(Alarm.Mode.CUSTOM, g));
        assertFalse(AlarmEditor.follows(Alarm.Mode.ROSTER, g));
        // 班表没有逐日时刻：双休用户走的也是这条路
        assertFalse(AlarmEditor.follows(Alarm.Mode.WORKDAY,
                new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, START, false)));
    }

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

    // ================= 备份兼容 =================

    /** 备份里不再出现这个键——字段删了，写出去的多余信息只会让人以为还有开关。 */
    @Test
    public void json_noLongerWritesFollowScheduleKey() {
        Alarm a = workday();
        assertFalse(Alarm.toJson(Collections.singletonList(a)).contains("followSchedule"));
    }

    /**
     * v1.6.0 写下的备份：WORKDAY + followSchedule=true。
     *
     * <p>读进来没有这个字段可填，但 mode 是 WORKDAY，行为与升级前完全一致。
     */
    @Test
    public void legacyBackup_withFollowScheduleKey_behavesTheSame() throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("id", 3);
        o.put("hour", 7);
        o.put("minute", 30);
        o.put("label", "上班");
        o.put("mode", Alarm.Mode.WORKDAY.ordinal());
        o.put("enabled", true);
        o.put("followSchedule", true);
        arr.put(o);

        List<Alarm> back = Alarm.fromJson(arr.toString());
        assertEquals(1, back.size());
        assertEquals(LocalTime.of(8, 0), back.get(0).timeOn(START, cycleRule()));
    }

    /**
     * v1.6.0 里 DAILY + followSchedule=true 是能存的（模式与开关正交）。
     *
     * <p>升级后这类闹钟退回固定时刻。这是本次唯一的破坏性变化，写在测试里而不是
     * 藏起来：那种组合本来就说不清「每天都响，但休息日按什么时刻」，v1.6.0 发出去
     * 不到一天，实际存在的条数应该为零。
     */
    @Test
    public void legacyBackup_dailyWithFlag_nowUsesOwnTime() throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("id", 4);
        o.put("hour", 6);
        o.put("minute", 45);
        o.put("label", "吃药");
        o.put("mode", Alarm.Mode.DAILY.ordinal());
        o.put("enabled", true);
        o.put("followSchedule", true);
        arr.put(o);

        List<Alarm> back = Alarm.fromJson(arr.toString());
        assertEquals(LocalTime.of(6, 45), back.get(0).timeOn(START, cycleRule()));
    }
}
