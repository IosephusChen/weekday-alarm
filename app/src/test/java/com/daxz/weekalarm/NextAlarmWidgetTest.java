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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.18.0：桌面小组件「下一个闹钟」内容计算。
 * v0.76.1：改版对齐首页今天状态卡——大字段今天状态、角标周次、副信息下次响铃，
 * 文案全部走 TodayCard，与首页逐字一致（一致性由 TodayCardTest 钉死，
 * 这里只钉「部件拿到的是首页那套数」）。
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
    public void noAlarms_subExplainsWhy() {
        // v0.76.1 起没有闹钟不再把「暂无提醒」顶上大字段——今天状态照样显示，
        // 副信息说清「没有启用的闹钟」，与首页空态同一个口径。
        // 注意：直接清 Prefs 会落到内置默认闹钟（07:30 起床，未迁移时 getAlarms 的兜底），
        // 要模拟「用户删光了」必须显式写空列表 + 标记已迁移。
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().putString(Prefs.KEY_ALARMS, "[]")
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true).apply();
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        assertTrue("副信息应说明没有闹钟，实际：" + m.sub,
                m.sub.contains("没有启用的闹钟") || m.sub.contains("今天上班，但没有要响的闹钟"));
        assertFalse("大字段仍是今天状态，不是空态占位", m.status.isEmpty());
    }

    @Test
    public void enabledWithDailyAlarm_showsNextRing() {
        List<Alarm> list = new ArrayList<>();
        // 每天 07:00 的「起床」闹钟（开启状态）
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        putAlarms(list);
        // 登记后 computeModel 才能查到「已登记」的闹钟
        ScheduleManager.rescheduleAll(ctx());

        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        assertTrue("副信息应含时刻与闹钟名，实际：" + m.sub,
                m.sub.contains("07:00 起床"));
        assertFalse("桌面不展示提醒总数这类统计", m.sub.contains("共"));
        // 角标与首页 chip 同一来源
        LocalDate today = LocalDate.now();
        assertEquals(WeekSchedule.describe(Prefs.getRule(ctx()), today), m.chip);
    }

    @Test
    public void vacationDay_statusAndSubMatchHomeCard() {
        // 确定性用例：把今天标成个人请假，大字段与副信息必须与首页卡片一致
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today);

        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        assertEquals("今天请假", m.status);
        assertEquals("个人请假 · 闹钟全部跳过", m.sub);
    }

    @Test
    public void sub_usesRelativeWordsForTodayAndTomorrow() {
        LocalDateTime now = LocalDateTime.now();
        NextAlarmWidget.WidgetModel today = NextAlarmWidget.computeModel(ctx(),
                new ScheduleManager.NextAlarm(now.withHour(23).withMinute(59), "测试", 1));
        assertTrue("当天的闹钟不写「今天」二字，只写时分（v0.30.0 规则），实际：" + today.sub,
                today.sub.contains("下次闹钟 23:59 测试"));

        NextAlarmWidget.WidgetModel tomorrow = NextAlarmWidget.computeModel(ctx(),
                new ScheduleManager.NextAlarm(now.plusDays(1).withHour(7).withMinute(0),
                        "测试", 1));
        assertTrue("明天的闹钟用「明天」，实际：" + tomorrow.sub,
                tomorrow.sub.contains("明天 07:00 测试"));
    }

    @Test
    public void status_isOneOfFourKnownStates() {
        // 大字段只允许四个值之一（TodayCard.statusText 的枚举），不允许自由文本
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx());
        assertTrue("实际状态：" + m.status,
                m.status.equals("今天休息") || m.status.equals("今天上班")
                        || m.status.equals("今天请假") || m.status.equals("今天加班"));
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
