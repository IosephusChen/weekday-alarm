package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
    public void nextWidget_inflates() {
        resetPrefs();
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        View root = inflate(NextAlarmWidget.buildViews(ctx(), m));
        assertEquals(m.status, ((TextView) root.findViewById(R.id.widget_hero)).getText().toString());
    }

    /** 4×4 整月卡的底部两个按钮：文案要真的写进 RemoteViews，而不是只存在于模型里。 */
    @Test
    public void monthWidget_actionButtonsShowText() {
        resetPrefs();
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        View root = inflate(MonthScheduleWidget.build(ctx()));

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
    public void monthWidget_markedSwitchesLayer() {
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

        View root = inflate(MonthScheduleWidget.build(ctx()));
        assertEquals("今天已标记：实底层可见", View.VISIBLE,
                root.findViewById(R.id.widget_today_action_on).getVisibility());
        assertEquals("半透明层应隐藏", View.GONE,
                root.findViewById(R.id.widget_today_action).getVisibility());
        assertEquals("明天没标记：仍是半透明层", View.VISIBLE,
                root.findViewById(R.id.widget_tomorrow_action).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.widget_tomorrow_action_on).getVisibility());
    }

    /**
     * 今天那一格的高亮层可见，其余六格不可见——高亮靠的就是这几个 View 的 visibility。
     *
     * <p>v1.53.0 起七格只剩 4×2 闹钟卡这一处，跟着搬过去测。
     */
    @Test
    public void nextWidget_highlightsOnlyToday() {
        resetPrefs();
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        View root = inflate(NextAlarmWidget.buildViews(ctx(), NextAlarmWidget.computeModel(ctx())));
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
    public void nextWidget_writesAllSevenCells() {
        resetPrefs();
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        View root = inflate(NextAlarmWidget.buildViews(ctx(), NextAlarmWidget.computeModel(ctx())));
        int[] ids = {R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3,
                R.id.label_4, R.id.label_5, R.id.label_6};
        List<WeekWidgetRender.DayCell> days = m.days;
        for (int i = 0; i < ids.length; i++) {
            TextView label = root.findViewById(ids[i]);
            assertNotNull(label);
            assertEquals("第 " + i + " 格文案", days.get(i).label, label.getText().toString());
        }
    }

    /** 两个按钮都要可点（点击挂在最外层容器上，内层只负责显示）。 */
    @Test
    public void monthWidget_actionWrappersAreClickable() {
        resetPrefs();
        View root = inflate(MonthScheduleWidget.build(ctx()));
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
                new NextAlarmWidget.WidgetModel("今天休息", "小周 · 休1天", "休息日 · 下次 明天 07:00 起床");
        View v = NextAlarmWidget.buildViews(ctx(), m).apply(ctx(), new FrameLayout(ctx()));

        assertNotNull("4×2 卡要有七格", v.findViewById(R.id.day_0));
        assertNotNull(v.findViewById(R.id.label_6));
        assertNotNull(v.findViewById(R.id.widget_hero));
        assertNotNull(v.findViewById(R.id.widget_sub));
    }

    /** 4×2 卡不放请假按钮——那是整月卡的事，塞进来会把七格挤没。 */
    @Test
    public void compactLayout_hasNoLeaveButtons() {
        NextAlarmWidget.WidgetModel m =
                new NextAlarmWidget.WidgetModel("今天休息", "小周 · 休1天", "休息日 · 下次 明天 07:00 起床");
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
     * 节气不进整月格子（v1.55.0）。
     *
     * <p>2026-10-23 是霜降。v1.51.2 起它会占掉这一格、把「班 / 休」顶掉——桌面日历上
     * 就看不出那天上不上班，只能靠颜色猜。现在节气只在 App 内日历（CalendarPanel）和
     * 首页今日信息里显示，小部件格子无论开关开还是关都不写节气。
     *
     * <p>顺便钉住「小部件不再读这个开关」：开关开与关的计算结果必须一致，
     * 否则说明代码又偷偷把节气接回了格子。
     */
    @Test
    public void monthCell_neverShowsSolarTerm() {
        Context c = ctx();
        LocalDate d = LocalDate.of(2026, 10, 23);
        Prefs.setWeekStart(c, Prefs.WEEK_START_MONDAY);
        try {
            Prefs.setSolarTerms(c, true);
            MonthWidgetRender.Model on = MonthWidgetRender.compute(c, d);
            assertNotEquals("霜降", on.tags[25]);
            assertTrue("节气退出后应显示班/休，实际是「" + on.tags[25] + "」",
                    "班".equals(on.tags[25]) || "休".equals(on.tags[25]));

            Prefs.setSolarTerms(c, false);
            MonthWidgetRender.Model off = MonthWidgetRender.compute(c, d);
            assertEquals("开关开与关的结果必须一致——小部件格子根本不看这个开关",
                    on.tags[25], off.tags[25]);
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
     * 两个变体的闹钟状态行恒定显示（v1.59.0 删开关后改成这条）。
     *
     * <p>历史：v1.51.1 加「本月班表显示闹钟」开关，为解决「闹钟占一整行把周次挤掉」；
     * v1.52.0 起闹钟并进标题行、只报状态不报时刻，且不再把「本周 · 大周」挤掉——
     * 开关的存在理由已经没了，用户反馈「这个开关没必要」，v1.59.0 删掉，改成恒定显示。
     *
     * <p>现在钉两点：两个变体都显示闹钟状态；周次与闹钟并排，互不挤掉。
     */
    @Test
    public void monthAlarmRow_alwaysShows() {
        Context c = ctx();
        View v4 = MonthScheduleWidget.build(c).apply(c, new FrameLayout(c));
        assertEquals("4×4 闹钟状态该显示（开关已删，恒定显示）", View.VISIBLE,
                v4.findViewById(R.id.widget_month_alarm).getVisibility());
        assertEquals("4×4 闹钟只报状态，不报具体时刻",
                "闹钟已开启", text(v4, R.id.widget_month_alarm));
        assertEquals("4×4 的周次不该被闹钟挤掉", View.VISIBLE,
                v4.findViewById(R.id.widget_month_sub).getVisibility());

        View v3 = MonthMinimalWidget.build(c).apply(c, new FrameLayout(c));
        assertEquals("4×3 闹钟状态也该显示", View.VISIBLE,
                v3.findViewById(R.id.widget_month_alarm).getVisibility());
        assertEquals("4×3 的周次不该被闹钟挤掉", View.VISIBLE,
                v3.findViewById(R.id.widget_month_sub).getVisibility());
    }

    /**
     * 状态要跟着实际走：闹钟全关时不能还写「已开启」（v1.52.0）。
     *
     * <p>4×2 本周班表那行原来是布局里写死的常量，关掉所有闹钟照样显示「闹钟已开启」。
     * 既然这次把整月卡改成一句话，就顺手做成真的。
     */
    @Test
    public void alarmStatus_reflectsActualState() {
        Context c = ctx();
        try {
            Prefs.setAlarms(c, new java.util.ArrayList<Alarm>());
            assertEquals("闹钟未开启", Widgets.alarmStatusText(c));

            Prefs.setAlarms(c, new java.util.ArrayList<>(
                    java.util.Collections.singletonList(
                            new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true))));
            assertEquals("闹钟已开启", Widgets.alarmStatusText(c));
        } finally {
            resetPrefs();
        }
    }
}
