package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.19：桌面小组件「下一个闹钟」内容计算。
 * 复用主 App 的 ScheduleManager，保证与 App 内状态一致。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class NextAlarmWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void putAlarms(List<Alarm> list) {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putInt(Prefs.KEY_HORIZON, 90)
                .apply();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    @Test
    public void noAlarms_showsEmptyHint() {
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        assertEquals("暂无提醒", m.title);
        assertTrue(m.subtitle.contains("90 天"));
    }

    @Test
    public void enabledWithDailyAlarm_showsNextAlarm() {
        List<Alarm> list = new ArrayList<>();
        // 每天 07:00 的「起床」闹钟（开启状态）
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        putAlarms(list);
        // 登记后 computeModel 才能查到「已登记」的闹钟
        ScheduleManager.rescheduleAll(ctx());

        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        assertEquals("主信息只放时刻，不被日期稀释", "07:00", m.title);
        assertTrue("副信息应含闹钟名称", m.subtitle.endsWith(" · 起床"));
        assertFalse("桌面不展示提醒总数这类统计", m.subtitle.contains("共"));
        assertFalse("主信息不再连日期", m.title.contains("月"));
    }

    @Test
    public void subtitle_usesRelativeWordsForTodayAndTomorrow() {
        LocalDateTime now = LocalDateTime.now();
        NextAlarmWidget.WidgetModel today = NextAlarmWidget.computeModel(ctx(),
                new ScheduleManager.NextAlarm(now.withHour(23).withMinute(59), "测试", 1), 0);
        assertTrue("今天的闹钟用「今天」，实际：" + today.subtitle,
                today.subtitle.startsWith("今天 · "));

        NextAlarmWidget.WidgetModel tomorrow = NextAlarmWidget.computeModel(ctx(),
                new ScheduleManager.NextAlarm(now.plusDays(1).withHour(7).withMinute(0),
                        "测试", 1), 0);
        assertTrue("明天的闹钟用「明天」，实际：" + tomorrow.subtitle,
                tomorrow.subtitle.startsWith("明天 · "));
    }

    @Test
    public void onUpdate_and_updateAll_safe() {
        // mgr 为 null 时不崩（Robolectric 下 AppWidgetManager 可能未初始化）
        NextAlarmWidget widget = new NextAlarmWidget();
        widget.onUpdate(ctx(), null, new int[]{});
        // 系统实例 + 空 id 列表也不崩
        widget.onUpdate(ctx(), android.appwidget.AppWidgetManager.getInstance(ctx()), new int[]{});
        // 主动推送入口（含 null 保护）不崩
        NextAlarmWidget.updateAll(ctx());
    }
}
