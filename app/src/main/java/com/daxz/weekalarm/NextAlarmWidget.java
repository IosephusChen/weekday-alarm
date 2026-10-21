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
        final boolean enabled;
        final String title;     // 主信息：下一个闹钟时间 / 状态文案
        final String subtitle;  // 副信息：标签 + 统计 / 引导提示
        WidgetModel(boolean enabled, String title, String subtitle) {
            this.enabled = enabled;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** 计算小组件要展示的内容；与主 App 共用同一套排班计算。 */
    static WidgetModel computeModel(Context ctx) {
        if (!Prefs.isEnabled(ctx)) {
            return new WidgetModel(false, "尚未开启", "打开「闹钟总开关」即自动排程");
        }
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
        if (!Prefs.isEnabled(ctx)) {
            return new WidgetModel(false, "尚未开启", "打开「闹钟总开关」即自动排程");
        }
        if (next == null) {
            return new WidgetModel(true, "暂无提醒",
                    "未来 " + Prefs.getHorizonDays(ctx) + " 天没有可排的闹钟");
        }
        LocalDateTime when = next.when;
        String time = when.format(FMT_MONTH_DAY) + " "
                + DOW[when.getDayOfWeek().getValue() - 1] + " "
                + String.format(Locale.getDefault(), "%02d:%02d", when.getHour(), when.getMinute());
        String sub = next.label + " · 未来 " + Prefs.getHorizonDays(ctx) + " 天共 " + count + " 个提醒";
        return new WidgetModel(true, time, sub);
    }

    static RemoteViews buildViews(Context ctx, WidgetModel m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_next_alarm);
        rv.setTextViewText(R.id.widget_status, m.enabled ? "已开启" : "未开启");
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
        mgr.updateAppWidget(id, buildViews(ctx, computeModel(ctx)));
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
        RemoteViews rv = buildViews(ctx, m);
        for (int id : ids) mgr.updateAppWidget(id, rv);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }
}
