package com.daxz.weekalarm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 桌面小组件（v1.19）：在桌面一眼看到「下一个闹钟」。
 * 计算逻辑完全复用主 App 的 ScheduleManager，保证与 App 内状态一致。
 * 更新策略：以主 App 主动推送为主（开关 / 设置变更 / 响铃后重排 / 开机恢复），
 * provider 的 updatePeriodMillis 为 30 分钟兜底（不是 0）——长期不打开 App 时，
 * 跨天后「今天」高亮最长会错约 30 分钟，Doze 期间更久，但没有即时 DATE_CHANGED 通道。
 */
public class NextAlarmWidget extends AppWidgetProvider {


    /** 小组件展示的数据模型（纯数据，便于单元测试，不经过 RemoteViews）。 */
    static class WidgetModel {
        final String title;     // 主信息：下一次响铃时刻 / 空状态
        final String subtitle;  // 副信息：日期 + 闹钟名
        WidgetModel(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * 计算小组件要展示的内容；与主 App 共用同一套排班计算。
     *
     * <p>v1.62.0 起重排信息层级：主信息只放**时刻**，副信息放**日期 + 闹钟名**。
     * 原来主信息是「10月26日 周一 07:00」一长串，时间被日期稀释；副信息还要缀
     * 「未来 90 天共 140 个提醒」——那是统计，不是桌面一眼要看的东西。
     */
    static WidgetModel computeModel(Context ctx) {
        return computeModel(ctx, ScheduleManager.nextRegisteredAlarm(ctx),
                ScheduleManager.countScheduled(ctx));
    }

    /**
     * 同上，但用调用方算好的结果（v1.29.0）。
     *
     * <p>主界面刷新时已经算过「下一个已登记闹钟」和「提醒总数」，这里再算一遍纯属重复；
     * 而 {@code nextRegisteredAlarm} 每次都要逐候选探测 PendingIntent，是跨进程调用。
     * v1.62.0 起提醒总数不再显示，{@code count} 只为兼容旧调用链保留。
     */
    static WidgetModel computeModel(Context ctx, ScheduleManager.NextAlarm next, int count) {
        if (next == null) {
            return new WidgetModel("暂无提醒",
                    "未来 " + Prefs.getHorizonDays(ctx) + " 天没有可排的闹钟");
        }
        LocalDateTime when = next.when;
        String title = TimeText.clock(when.getHour(), when.getMinute());
        String label = next.label == null || next.label.isEmpty() ? "闹钟" : next.label;
        String subtitle = datePart(when.toLocalDate()) + " · " + label;
        return new WidgetModel(title, subtitle);
    }

    /** 副信息里的日期部分：今天 / 明天用相对词，其余「月日 + 星期」。 */
    private static String datePart(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "今天";
        if (d.equals(today.plusDays(1))) return "明天";
        return TimeText.monthDayWeek(d);
    }

    /**
     * 4×2 固定布局：闹钟两行 + 本周七格（v1.51.0）。
     *
     * <p>v1.50.0 之前这里只有闹钟两行，七格是「拉到 4×3 才出现」的第二档布局。
     * 尺寸锁死之后没有第二档了，七格直接并进来——用户实际用下来，
     * 165dp 的卡片放「闹钟 + 七格」正好，比原来空着半张卡有用。
     */
    static RemoteViews buildViews(Context ctx, WidgetModel m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_next_alarm);
        // v1.58.0：状态跟着实际走。原来写死「已开启」——闹钟全关时这张卡显示
        // 「已开启 + 暂无提醒」，自相矛盾。整月两张卡 v1.52.0 就接上了
        // Widgets.alarmStatusText，4×2 漏接，这次补上，几张卡一个口径。
        rv.setTextViewText(R.id.widget_status, Widgets.alarmStatusText(ctx));
        rv.setTextViewText(R.id.widget_title, m.title);
        rv.setTextViewText(R.id.widget_sub, m.subtitle);

        WeekWidgetRender.WeekModel week = WeekWidgetRender.computeModel(ctx);
        WeekWidgetRender.fillWeekCells(rv, ctx, week);

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

    /**
     * 尺寸变化回调。
     *
     * <p>v1.51.0 起布局只有一份、尺寸由 resizeMode 锁死，理论上不会再触发。
     * 保留是因为部分启动器首次绑定后才补 options，重推一次没有坏处。
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
        for (int id : ids) mgr.updateAppWidget(id, buildViews(ctx, m));
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }
}
