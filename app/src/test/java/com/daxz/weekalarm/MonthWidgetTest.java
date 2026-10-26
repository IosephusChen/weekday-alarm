package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 4×4 整月版（v0.64.0）。
 *
 * <p>这一版最容易错的是「第一格从哪开始」——2026-10-01 是周四，
 * 周一开头时前面要空 3 格；算成 4 格的话整月每一天都会错位一格，
 * 而界面上看不出来（每格都还是「班/休」，只是日子对应错了）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MonthWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
    }

    private MonthWidgetRender.Model oct2026() {
        return MonthWidgetRender.compute(ctx(), LocalDate.of(2026, 10, 22));
    }

    @Test
    public void titleAndLeadingBlanks() {
        MonthWidgetRender.Model m = oct2026();
        assertEquals("2026 年 10 月", m.title);
        // 2026-10-01 是周四；周一开头时前三格（一二三）留空
        assertEquals("", m.nums[0]);
        assertEquals("", m.nums[1]);
        assertEquals("", m.nums[2]);
        assertEquals("1", m.nums[3]);
    }

    @Test
    public void everyDayGetsTheRightTag() {
        MonthWidgetRender.Model m = oct2026();
        // 取月中普通的一周：10/12 周一班、10/17 周六休。
        // 不能用 10/1 —— 那天是国庆，内置节假日会把它算成「休」。
        assertEquals("12", m.nums[14]);
        assertEquals("班", m.tags[14]);
        assertEquals("17", m.nums[19]);
        assertEquals("休", m.tags[19]);
        assertEquals("31", m.nums[3 + 30]);  // 10/31 落在第 34 格
        assertEquals("", m.nums[3 + 31]);    // 之后留空
    }

    /**
     * 法定节假日名优先于「休」（v0.65.0）。
     *
     * <p>颜色仍然是「休」的淡紫——上班还是休息靠颜色说，字换成节日名不丢信息。
     * 这一点与日历页一致：那边也是底色表达班休、文字写节日。
     */
    @Test
    public void holidayNameReplacesRestLabel() {
        MonthWidgetRender.Model m = oct2026();
        assertEquals("1", m.nums[3]);
        assertEquals("国庆", m.tags[3]);
        assertEquals("国庆那天仍按休息着色", 0xFFC9C4FF, m.colors[3]);
    }

    /**
     * 农历节日跟着设置里的开关走。
     *
     * <p>用「七夕」而不是「中秋」：中秋在 2026 年放假，属于法定节假日，
     * 走的是另一条更高优先级的路径，关掉农历开关也照样显示——那是应该的。
     */
    @Test
    public void lunarFestival_followsSetting() {
        java.time.LocalDate qixi = null;
        for (int d = 1; d <= 31; d++) {
            java.time.LocalDate x = java.time.LocalDate.of(2026, 8, d);
            if ("七夕".equals(LunarCalendar.festival(x))) { qixi = x; break; }
        }
        assertNotNull("2026 年 8 月应能找到七夕", qixi);
        assertTrue("七夕不是法定假日，才能测到开关",
                WeekSchedule.dayType(qixi, Prefs.getRule(ctx())) != WeekSchedule.DayType.HOLIDAY);

        setFlag(Prefs.KEY_LUNAR_FESTIVALS, false);
        MonthWidgetRender.Model off = MonthWidgetRender.compute(ctx(), qixi);
        assertTrue("关了就不该出现节日名", !"七夕".equals(off.tags[indexOf(off, qixi)]));

        setFlag(Prefs.KEY_LUNAR_FESTIVALS, true);
        MonthWidgetRender.Model on = MonthWidgetRender.compute(ctx(), qixi);
        assertEquals("七夕", on.tags[indexOf(on, qixi)]);
    }

    /** 基督教节日同理。 */
    @Test
    public void christianFestival_followsSetting() {
        java.time.LocalDate xmas = java.time.LocalDate.of(2026, 12, 25);
        assertEquals("圣诞", ChristianCalendar.festival(xmas));

        setFlag(Prefs.KEY_CHRISTIAN_FESTIVALS, false);
        MonthWidgetRender.Model off = MonthWidgetRender.compute(ctx(), xmas);
        assertTrue(!("圣诞".equals(off.tags[indexOf(off, xmas)])));

        setFlag(Prefs.KEY_CHRISTIAN_FESTIVALS, true);
        MonthWidgetRender.Model on = MonthWidgetRender.compute(ctx(), xmas);
        assertEquals("圣诞", on.tags[indexOf(on, xmas)]);
    }

    /**
     * 直接写 SharedPreferences 并 commit。
     *
     * <p>不在测试里用 {@code Prefs.setXxx}：那边是 {@code apply()}，
     * Robolectric 下紧接着读会拿到旧值，测试就会假绿或假红。
     */
    private void setFlag(String key, boolean v) {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().putBoolean(key, v).commit();
    }

    /** 42 格里找某一天的下标。 */
    private static int indexOf(MonthWidgetRender.Model m, java.time.LocalDate d) {
        String want = String.valueOf(d.getDayOfMonth());
        for (int i = 0; i < 42; i++) {
            if (want.equals(m.nums[i]) && !m.tags[i].isEmpty()) return i;
        }
        return -1;
    }

    @Test
    public void todayIsMarkedOnce() {
        MonthWidgetRender.Model m = oct2026();
        boolean[] seen = {false};
        for (int i = 0; i < 42; i++) {
            if (m.today[i]) {
                assertTrue("今天只能标一次", !seen[0]);
                seen[0] = true;
                assertEquals("22", m.nums[i]);
            }
        }
        assertTrue("22 号必须被标出来", seen[0]);
    }

    /** 请假 / 加班覆盖班休标记，同一天两个都有时写「假加」。 */
    @Test
    public void leaveAndOvertimeWinOverWorkRest() {
        Prefs.addVacation(ctx(), LocalDate.of(2026, 10, 12));   // 本来是班
        Prefs.addOvertime(ctx(), LocalDate.of(2026, 10, 17));   // 本来是休

        MonthWidgetRender.Model m = oct2026();
        assertEquals("假", m.tags[14]);
        assertEquals("加", m.tags[19]);
    }

    /**
     * 底部统计（v0.80.0）：一天不差地数完整月。
     *
     * <p>不写死「22 天班」这种数字——国庆、调休补班都在 {@link HolidayData} 里，
     * 内置数据一变数字就变。这里用同一套口径独立数一遍，和模型对账。
     */
    @Test
    public void stats_countsWholeMonth() {
        MonthWidgetRender.Model m = oct2026();
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        int work = 0;
        int len = 31;
        for (int day = 1; day <= len; day++) {
            LocalDate d = LocalDate.of(2026, 10, day);
            boolean baseWork = DayOffAction.isWorkDay(WeekSchedule.dayType(d, rule));
            boolean leave = Prefs.isVacation(ctx(), d);
            boolean overtime = Prefs.isOvertime(ctx(), d);
            if (baseWork ? !leave : overtime) work++;
        }
        assertEquals(work + " 天班 · " + (len - work) + " 天休", m.stats);
    }

    /** 请了一天假，统计里的「班」要跟着少一天——它得和格子说同一件事。 */
    @Test
    public void stats_followsVacationMark() {
        String before = oct2026().stats;
        Prefs.addVacation(ctx(), LocalDate.of(2026, 10, 12));   // 本来是班
        String after = oct2026().stats;
        assertNotEquals("标了假统计该变", before, after);

        int beforeWork = Integer.parseInt(before.substring(0, before.indexOf(" 天班")));
        int afterWork = Integer.parseInt(after.substring(0, after.indexOf(" 天班")));
        assertEquals("少上一天班", beforeWork - 1, afterWork);
    }

    /** 假期倒计时与首页今天卡同源，不能各算各的。 */
    @Test
    public void next_matchesHomeCard() {
        LocalDate today = LocalDate.of(2026, 10, 22);
        ScheduleManager.NextAlarm next = new ScheduleManager.NextAlarm(
                today.plusDays(1).atTime(7, 30), "起床", 1);
        MonthWidgetRender.Model m = MonthWidgetRender.compute(ctx(), today, next);
        assertTrue("底部第二段与首页今天卡同一句，实际：" + m.footerText(),
                m.footerText().endsWith(" · " + TodayCard.nextTail(next, today)));
        assertTrue("应为「下次闹钟 明天 07:30」，实际：" + m.footerText(),
                m.footerText().contains("下次闹钟 明天 07:30"));
    }

    @Test
    public void next_hiddenWhenNoAlarm() {
        MonthWidgetRender.Model m = MonthWidgetRender.compute(ctx(), LocalDate.of(2026, 10, 22), null);
        assertEquals("没有启用的闹钟时只剩统计，不留半句空话",
                m.stats, m.footerText());
    }

    /** 布局必须真的能 inflate——RemoteViews 的允许列表很窄，写错一个控件就整块加载失败。 */
    @Test
    public void monthLayout_inflates() {
        RemoteViews rv = MonthWidgetRender.build(ctx(), oct2026());
        View v = rv.apply(ctx(), new FrameLayout(ctx()));
        assertNotNull(v);
        assertNotNull(v.findViewById(R.id.widget_month_title));
        assertNotNull(v.findViewById(R.id.m_num_0));
        assertNotNull(v.findViewById(R.id.m_tag_41));
    }
}
