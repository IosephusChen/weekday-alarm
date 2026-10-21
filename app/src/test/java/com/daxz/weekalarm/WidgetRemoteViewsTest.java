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

    /** 按钮文案要真的写进 RemoteViews，而不是只存在于模型里。 */
    @Test
    public void weekWidget_actionButtonShowsText() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        TextView action = root.findViewById(R.id.widget_week_action);
        assertNotNull("应有「明天不上班」按钮", action);
        assertEquals(m.action, action.getText().toString());
        assertTrue("按钮文案要说明天，实际：" + action.getText(),
                action.getText().toString().startsWith("明天"));
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

    /** 明天请假按钮点击后要发到 BedtimeReceiver 的切换动作上。 */
    @Test
    public void weekWidget_actionClickTargetsReceiver() {
        resetPrefs();
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        View root = inflate(WeekScheduleWidget.buildViews(ctx(), m));
        assertTrue("按钮必须可点", root.findViewById(R.id.widget_week_action).isClickable());
    }
}
