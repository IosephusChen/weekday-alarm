package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
}
