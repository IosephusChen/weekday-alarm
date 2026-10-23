package com.daxz.weekalarm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 桌面小组件（v1.19）：在桌面一眼看到「下一个闹钟」。
 * 计算逻辑完全复用主 App 的 ScheduleManager，保证与 App 内状态一致。
 * 更新策略：以主 App 主动推送为主（开关 / 设置变更 / 响铃后重排 / 开机恢复），
 * 故 AppWidgetProviderInfo 的 updatePeriodMillis 设为 0，不依赖系统周期性刷新。
 */
public class NextAlarmWidget extends AppWidgetProvider {

    private static final DateTimeFormatter FMT_MONTH_DAY =
            DateTimeFormatter.ofPattern("M月d日");
    private static final String[] DOW = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 小组件展示的数据模型（纯数据，便于单元测试，不经过 RemoteViews）。 */
    static class WidgetModel {
        final String title;     // 主信息：下一个闹钟时间 / 状态文案
        final String subtitle;  // 副信息：标签 + 统计
        WidgetModel(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** 计算小组件要展示的内容；与主 App 共用同一套排班计算。 */
    static WidgetModel computeModel(Context ctx) {
        return computeModel(ctx, ScheduleManager.nextRegisteredAlarm(ctx),
                ScheduleManager.countScheduled(ctx));
    }

    /**
     * 同上，但用调用方算好的结果（v1.29.0）。
     *
     * <p>主界面刷新时已经算过「下一个已登记闹钟」和「提醒总数」，这里再算一遍纯属重复；
     * 而 {@code nextRegisteredAlarm} 每次都要逐候选探测 PendingIntent，是跨进程调用。
     */
    static WidgetModel computeModel(Context ctx, ScheduleManager.NextAlarm next, int count) {
        if (next == null) {
            return new WidgetModel("暂无提醒",
                    "未来 " + Prefs.getHorizonDays(ctx) + " 天没有可排的闹钟");
        }
        LocalDateTime when = next.when;
        String time = when.format(FMT_MONTH_DAY) + " "
                + DOW[when.getDayOfWeek().getValue() - 1] + " "
                + String.format(Locale.getDefault(), "%02d:%02d", when.getHour(), when.getMinute());
        String sub = next.label + " · 未来 " + Prefs.getHorizonDays(ctx) + " 天共 " + count + " 个提醒";
        return new WidgetModel(time, sub);
    }

    static RemoteViews buildViews(Context ctx, WidgetModel m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_next_alarm);
        rv.setTextViewText(R.id.widget_status, "已开启");
        rv.setTextViewText(R.id.widget_title, m.title);
        rv.setTextViewText(R.id.widget_sub, m.subtitle);
        // 点击整卡打开 App（v1.19）
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);
        return rv;
    }

    private static void updateAppWidget(Context ctx, AppWidgetManager mgr, int id) {
        mgr.updateAppWidget(id, buildFor(ctx, mgr, id, computeModel(ctx)));
    }

    /** 4×3 起换大布局，4×4 再多一行按钮（v1.49.12）。 */
    private static final int H_TALL = 150;
    private static final int H_FULL = 220;

    /**
     * 按当前尺寸挑布局。
     *
     * <p>4×2 只有闹钟，拉高后下面一大片空白——所以 4×3 起补上本周七格，
     * 4×4 再加「今天 / 明天不上班」。每一档都比上一档多东西，
     * 拖动时不会出现「拉高了但什么都没变」。
     */
    private static RemoteViews buildFor(Context ctx, AppWidgetManager mgr, int id, WidgetModel m) {
        int h = 0;
        android.os.Bundle o = mgr.getAppWidgetOptions(id);
        if (o != null) h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        if (h < H_TALL) return buildViews(ctx, m);
        return buildTall(ctx, m, h >= H_FULL);
    }

    /** 大尺寸：闹钟 + 本周七格（+ 可选的两个请假按钮）。 */
    static RemoteViews buildTall(Context ctx, WidgetModel m, boolean withActions) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_next_alarm_tall);
        rv.setTextViewText(R.id.widget_status, "已开启");
        rv.setTextViewText(R.id.widget_title, m.title);
        rv.setTextViewText(R.id.widget_sub, m.subtitle);

        WeekScheduleWidget.WeekModel week = WeekScheduleWidget.computeModel(ctx);
        WeekScheduleWidget.fillWeekCells(rv, ctx, week);

        rv.setViewVisibility(R.id.action_row,
                withActions ? android.view.View.VISIBLE : android.view.View.GONE);
        if (withActions) {
            WeekScheduleWidget.bindAction(rv, ctx, week.todayAction, week.todayMarked, 0,
                    R.id.widget_today_action, R.id.widget_today_action_on,
                    R.id.wrap_today_action, 22);
            WeekScheduleWidget.bindAction(rv, ctx, week.tomorrowAction, week.tomorrowMarked, 1,
                    R.id.widget_tomorrow_action, R.id.widget_tomorrow_action_on,
                    R.id.wrap_tomorrow_action, 23);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);
        return rv;
    }

    /**
     * 拖动改尺寸时重排（v1.49.12）。
     *
     * <p>原来没实现这个回调，宿主给多大尺寸都返回同一份视图，
     * 拉高就是「内容贴着顶、下面一片空」。
     */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, android.os.Bundle newOptions) {
        if (mgr == null) return;
        updateAppWidget(ctx, mgr, id);
    }

    /** 主 App 在关键时机主动推送刷新（开关、设置变更、响铃后重排、开机恢复）。 */
    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NextAlarmWidget.class));
        if (ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }

    /** 同上，复用调用方已算好的结果，省掉一次「下一个已登记闹钟」的探测（v1.29.0）。 */
    public static void updateAll(Context ctx, ScheduleManager.NextAlarm next, int count) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NextAlarmWidget.class));
        if (ids == null || ids.length == 0) return;   // 桌面没放小组件：一次模型都不算
        WidgetModel m = computeModel(ctx, next, count);
        for (int id : ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id, m));
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }
}
