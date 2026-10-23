package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.List;

/**
 * 小组件必须真的能 inflate（v1.32）。
 *
 * <p>这条测试的由来：本周班表组件从 v1.20 起就用 {@code <View>} 做「今天」的高亮层，
 * 而 RemoteViews 的允许列表里没有 {@code android.view.View}——于是它在启动器里
 * 从来没成功加载过，一直显示「载入窗口小部件时出现问题」。所有单测都是直接读
 * {@code computeModel} 的数据模型，没人碰过真正的 RemoteViews，所以这个洞一直没被发现。
 *
 * <p>从此以后：任何小组件布局的改动，都要过一遍 {@code RemoteViews.apply}。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WidgetRemoteViewsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
    }

    /** 把 RemoteViews 真正 inflate 成一个 View 树；失败会抛 InflateException。 */
    private View inflate(RemoteViews rv) {
        FrameLayout parent = new FrameLayout(ctx());
        View v = rv.apply(ctx(), parent);
        assertNotNull("RemoteViews 应能 inflate 出视图", v);
        return v;
    }

    @Test
    public void weekWidget_inflates() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        assertNotNull("应能按 id 找到标题", root.findViewById(R.id.widget_week_title));
    }

    @Test
    public void nextWidget_inflates() {
        resetPrefs();
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        View root = inflate(NextAlarmWidget.buildViews(ctx(), m));
        assertEquals(m.title, ((TextView) root.findViewById(R.id.widget_title)).getText().toString());
    }

    /** 两个按钮的文案要真的写进 RemoteViews，而不是只存在于模型里。 */
    @Test
    public void weekWidget_actionButtonsShowText() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));

        TextView today = root.findViewById(R.id.widget_today_action);
        TextView tomorrow = root.findViewById(R.id.widget_tomorrow_action);
        assertNotNull("应有「今天不上班」按钮", today);
        assertNotNull("应有「明天不上班」按钮", tomorrow);
        assertEquals(m.todayAction, today.getText().toString());
        assertEquals(m.tomorrowAction, tomorrow.getText().toString());
        assertTrue("今天按钮要写今天，实际：" + today.getText(),
                today.getText().toString().startsWith("今天"));
        assertTrue("明天按钮要写明天，实际：" + tomorrow.getText(),
                tomorrow.getText().toString().startsWith("明天"));
    }

    /** 未标记时显示半透明层、藏起实底层；标记后反过来。 */
    @Test
    public void weekWidget_markedSwitchesLayer() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        WeekSchedule.DayType type = WeekSchedule.dayType(today, Prefs.getRule(ctx()));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
        if (work) {
            Prefs.addVacation(ctx(), today);
        } else {
            Prefs.addOvertime(ctx(), today);
        }

        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        assertEquals("今天已标记：实底层可见", View.VISIBLE,
                root.findViewById(R.id.widget_today_action_on).getVisibility());
        assertEquals("半透明层应隐藏", View.GONE,
                root.findViewById(R.id.widget_today_action).getVisibility());
        assertEquals("明天没标记：仍是半透明层", View.VISIBLE,
                root.findViewById(R.id.widget_tomorrow_action).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.widget_tomorrow_action_on).getVisibility());
    }

    /** 今天那一格的高亮层可见，其余六格不可见——高亮靠的就是这几个 View 的 visibility。 */
    @Test
    public void weekWidget_highlightsOnlyToday() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        int[] ids = {R.id.hl_0, R.id.hl_1, R.id.hl_2, R.id.hl_3,
                R.id.hl_4, R.id.hl_5, R.id.hl_6};
        int todayIndex = -1;
        for (int i = 0; i < m.days.size(); i++) {
            if (m.days.get(i).today) todayIndex = i;
        }
        assertTrue("一周里总该有一天是今天", todayIndex >= 0);
        for (int i = 0; i < ids.length; i++) {
            View hl = root.findViewById(ids[i]);
            assertNotNull("高亮层 " + i + " 应存在", hl);
            assertEquals("第 " + i + " 格高亮状态不对",
                    i == todayIndex ? View.VISIBLE : View.GONE, hl.getVisibility());
        }
    }

    /** 七格的文字也要写进去（班 / 休 / 假 / 补）。 */
    @Test
    public void weekWidget_writesAllSevenCells() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        int[] ids = {R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3,
                R.id.label_4, R.id.label_5, R.id.label_6};
        List<WeekScheduleWidget.DayCell> days = m.days;
        for (int i = 0; i < ids.length; i++) {
            TextView label = root.findViewById(ids[i]);
            assertNotNull(label);
            assertEquals("第 " + i + " 格文案", days.get(i).label, label.getText().toString());
        }
    }

    /** 两个按钮都要可点（点击挂在最外层容器上，内层只负责显示）。 */
    @Test
    public void weekWidget_actionWrappersAreClickable() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        assertTrue("今天按钮必须可点",
                root.findViewById(R.id.wrap_today_action).isClickable());
        assertTrue("明天按钮必须可点",
                root.findViewById(R.id.wrap_tomorrow_action).isClickable());
    }

    // ================= 大尺寸分档（v1.49.12）=================

    /** 4×3 起：闹钟 + 本周七格，按钮先不出现。 */
    // ================= 4×2 固定布局（v1.51.0）=================

    /**
     * 4×2 卡必须带本周七格。
     *
     * <p>v1.50.0 这张卡只有两行文字，七格是「高度 ≥150dp 才出现」的第二档。
     * 用户实际看到的是巧合——卡片实测 165dp，正好跨过阈值。现在固化成一份布局，
     * 这条断言守住「不管高度多少，七格都在」。
     */
    @Test
    public void compactLayout_alwaysHasWeekCells() {
        NextAlarmWidget.WidgetModel m =
                new NextAlarmWidget.WidgetModel("起床", "明天 07:00 会响");
        View v = NextAlarmWidget.buildViews(ctx(), m).apply(ctx(), new FrameLayout(ctx()));

        assertNotNull("4×2 卡要有七格", v.findViewById(R.id.day_0));
        assertNotNull(v.findViewById(R.id.label_6));
        assertNotNull(v.findViewById(R.id.widget_title));
        assertNotNull(v.findViewById(R.id.widget_sub));
    }

    /** 4×2 卡不放请假按钮——那是整月卡的事，塞进来会把七格挤没。 */
    @Test
    public void compactLayout_hasNoLeaveButtons() {
        NextAlarmWidget.WidgetModel m =
                new NextAlarmWidget.WidgetModel("起床", "明天 07:00 会响");
        View v = NextAlarmWidget.buildViews(ctx(), m).apply(ctx(), new FrameLayout(ctx()));

        assertNull("4×2 不放请假按钮", v.findViewById(R.id.wrap_today_action));
        assertNull(v.findViewById(R.id.wrap_tomorrow_action));
    }

    /** 整月版也必须有请假按钮——v1.49.10 切到整月后这两个按钮一度消失。 */
    @Test
    public void monthLayout_keepsLeaveButtons() {
        View v = MonthWidgetRender.build(ctx(), MonthWidgetRender.compute(ctx(), LocalDate.now()))
                .apply(ctx(), new FrameLayout(ctx()));

        assertNotNull("整月版不能丢请假按钮", v.findViewById(R.id.wrap_today_action));
        assertNotNull(v.findViewById(R.id.wrap_tomorrow_action));
        assertNotNull(v.findViewById(R.id.widget_today_action));
    }

    /**
     * 整月表头必须跟「每周第一天」设置联动。
     *
     * <p>v1.50.0 的 bug：表头在布局里写死「一 二 三 四 五 六 日」，
     * 而格子按 Prefs.isSundayFirst 排。设成周日开头时，1 号落在第 5 列，
     * 表头第 5 列却写着「五」——整月日历错位一格。
     *
     * <p>2026-10-01 是周四，用它做锚点：周日开头落在下标 4，周一开头落在下标 3。
     */
    @Test
    public void monthHeader_followsWeekStartSetting() {
        Context c = ctx();
        LocalDate anchor = LocalDate.of(2026, 10, 23);
        try {
            Prefs.setWeekStart(c, Prefs.WEEK_START_SUNDAY);
            View sun = MonthWidgetRender.build(c, MonthWidgetRender.compute(c, anchor))
                    .apply(c, new FrameLayout(c));
            assertEquals("周日开头时第一列表头", "日", text(sun, R.id.m_head_0));
            assertEquals("2026-10-01 应在下标 4", "1", text(sun, R.id.m_num_4));
            assertEquals("第 5 列表头必须与 1 号所在列一致", "四", text(sun, R.id.m_head_4));

            Prefs.setWeekStart(c, Prefs.WEEK_START_MONDAY);
            View mon = MonthWidgetRender.build(c, MonthWidgetRender.compute(c, anchor))
                    .apply(c, new FrameLayout(c));
            assertEquals("周一开头时第一列表头", "一", text(mon, R.id.m_head_0));
            assertEquals("2026-10-01 应在下标 3", "1", text(mon, R.id.m_num_3));
            assertEquals("第 4 列表头必须与 1 号所在列一致", "四", text(mon, R.id.m_head_3));
        } finally {
            Prefs.setWeekStart(c, Prefs.WEEK_START_MONDAY);
        }
    }

    /**
     * 节气要进整月格子，并且受开关控制（v1.51.2）。
     *
     * <p>2026-10-23 是霜降，周一开头时落在下标 25（(23−1)+3）。
     * 关掉开关后应该退回「班 / 休」——不是空着。
     */
    @Test
    public void monthCell_showsSolarTermAndRespectsSwitch() {
        Context c = ctx();
        LocalDate d = LocalDate.of(2026, 10, 23);
        Prefs.setWeekStart(c, Prefs.WEEK_START_MONDAY);
        try {
            Prefs.setSolarTerms(c, true);
            MonthWidgetRender.Model on = MonthWidgetRender.compute(c, d);
            assertEquals("霜降", on.tags[25]);

            Prefs.setSolarTerms(c, false);
            MonthWidgetRender.Model off = MonthWidgetRender.compute(c, d);
            assertTrue("关掉节气后应退回班/休，实际是「" + off.tags[25] + "」",
                    "班".equals(off.tags[25]) || "休".equals(off.tags[25]));
        } finally {
            Prefs.setSolarTerms(c, true);
            Prefs.setWeekStart(c, Prefs.WEEK_START_MONDAY);
        }
    }

    private static String text(View root, int id) {
        TextView tv = root.findViewById(id);
        assertNotNull("找不到视图 " + id, tv);
        return tv.getText().toString();
    }

    /**
     * 「本月班表显示闹钟」开关必须同时管住三个变体（v1.51.1）。
     *
     * <p>4×4 的闹钟是独立一行；4×3 和「本周 · 大周」叠在标题行右侧的同一个
     * FrameLayout 里，只能二选一——这里把两个布局的差异一起钉住。
     */
    @Test
    public void monthAlarmRow_followsGlobalSwitch() {
        Context c = ctx();
        try {
            Prefs.setMonthWidgetAlarm(c, true);
            View on4 = MonthScheduleWidget.build(c).apply(c, new FrameLayout(c));
            assertEquals("4×4 闹钟行该显示", View.VISIBLE,
                    on4.findViewById(R.id.widget_month_alarm).getVisibility());

            View on3 = MonthCompactWidget.build(c).apply(c, new FrameLayout(c));
            assertEquals("4×3 闹钟该显示", View.VISIBLE,
                    on3.findViewById(R.id.widget_month_alarm).getVisibility());
            assertEquals("4×3 显示闹钟时「本周 · 大周」要让位", View.GONE,
                    on3.findViewById(R.id.widget_month_sub).getVisibility());

            Prefs.setMonthWidgetAlarm(c, false);
            View off4 = MonthScheduleWidget.build(c).apply(c, new FrameLayout(c));
            assertEquals("关掉后 4×4 闹钟行该隐藏", View.GONE,
                    off4.findViewById(R.id.widget_month_alarm).getVisibility());

            View off3 = MonthCompactWidget.build(c).apply(c, new FrameLayout(c));
            assertEquals("关掉后 4×3 闹钟该隐藏", View.GONE,
                    off3.findViewById(R.id.widget_month_alarm).getVisibility());
            assertEquals("关掉后「本周 · 大周」要回来", View.VISIBLE,
                    off3.findViewById(R.id.widget_month_sub).getVisibility());
        } finally {
            Prefs.setMonthWidgetAlarm(c, true);
        }
    }
}
