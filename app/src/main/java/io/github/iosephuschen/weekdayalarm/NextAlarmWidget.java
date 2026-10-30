package io.github.iosephuschen.weekdayalarm;

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
 * 桌面小组件（v0.18.0）：在桌面一眼看到「下次什么时候响、今天上不上班」。
 *
 * <p><b>v0.76.1 改版</b>：信息结构对齐 App 首页的「今天状态卡」——
 * 大字段放今天状态（今天休息 / 今天上班 / 今天请假 / 今天加班），
 * 右侧角标放周次（小周 · 休1天），副信息放「下次 明天 07:00 起床」，
 * 底部仍是本周七格。原来大字段放的是闹钟时刻，与首页的「状态优先」不是一个层级，
 * 同一天两处给的重点不一样。文案计算全部走 {@link TodayCard}，与首页逐字一致。
 *
 * <p><b>为什么不放「距下次响铃」秒表倒计时</b>：widget 的 updatePeriodMillis
 * 系统最快按 30 分钟投递，一个秒级倒计时会在两次刷新之间冻结半小时，
 * 显示一个静止的「20:35:06」比不显示更糟。要看着跳的倒计时请进 App。
 *
 * <p>计算逻辑复用主 App 的 ScheduleManager / WeekSchedule，保证与 App 内状态一致。
 * 更新策略：以主 App 主动推送为主（开关 / 设置变更 / 响铃后重排 / 开机恢复），
 * provider 的 updatePeriodMillis 为 30 分钟兜底（不是 0）——长期不打开 App 时，
 * 跨天后「今天」高亮最长会错约 30 分钟，Doze 期间更久，但没有即时 DATE_CHANGED 通道。
 */
public class NextAlarmWidget extends AppWidgetProvider {

    /** 小组件展示的数据模型（纯数据，便于单元测试，不经过 RemoteViews）。 */
    static class WidgetModel {
        final String status;   // 大字段：今天休息 / 今天上班 / 今天请假 / 今天加班
        final String chip;     // 角标：小周 · 休1天
        final String sub;      // 副信息：休息日 · 下次 明天 07:00 起床
        WidgetModel(String status, String chip, String sub) {
            this.status = status;
            this.chip = chip;
            this.sub = sub;
        }
    }

    /**
     * 计算小组件要展示的内容；与主 App 首页状态卡共用同一套排班与文案计算。
     *
     * <p>v0.76.1 起改算 {@code nextRing}（下次真正会响的闹钟）而不是
     * {@code nextRegisteredAlarm}（系统里已登记的下一个 PendingIntent）：
     * 首页副信息用的是前者，部件要逐字对齐就只能用前者。前者是纯内存计算，
     * 后者才要逐候选探测 PendingIntent（跨进程 IPC）——顺手还把首页刷新里
     * 那次 IPC 探测省掉了。
     */
    static WidgetModel computeModel(Context ctx) {
        // v0.91.6：补上 skipOnce。之前这里和首页一样调的是六参数重载，
        // 点完「这次不响」部件照旧显示今天那一趟，和首页一起错。
        return computeModel(ctx, ScheduleManager.nextRing(
                Prefs.getAlarms(ctx), Prefs.getRule(ctx),
                Prefs.getVacationDates(ctx), Prefs.getOvertimeDates(ctx),
                Prefs.getSkipOnce(ctx),
                LocalDateTime.now(), Prefs.getHorizonDays(ctx)));
    }

    /**
     * 同上，但「下一次响铃」由调用方给定（v0.45.0 的性能预留，现主要为测试服务：
     * 构造一个确定的 NextAlarm，就能钉死今天 / 明天 / 后天的相对词）。
     */
    static WidgetModel computeModel(Context ctx, ScheduleManager.NextAlarm next) {
        LocalDate today = LocalDate.now();
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        WeekSchedule.DayType todayType = WeekSchedule.dayType(today, rule);
        boolean vacation = Prefs.isVacation(ctx, today);
        boolean baseWork = DayOffAction.isWorkDay(todayType);
        boolean overtime = !vacation && !baseWork && Prefs.isOvertime(ctx, today);
        boolean work = (baseWork && !vacation) || overtime;
        String dayTag = TodayCard.dayTagText(todayType, vacation, overtime, baseWork);
        return new WidgetModel(
                TodayCard.statusText(vacation, overtime, work),
                TodayCard.chipText(rule, today),
                TodayCard.subText(dayTag, next, today, work, vacation));
    }

    /**
     * 4×2 固定布局：状态两行 + 本周七格（v0.68.0 并入七格，v0.76.1 重排信息层级）。
     *
     * <p>v0.67.0 之前这里只有闹钟两行，七格是「拉到 4×3 才出现」的第二档布局。
     * 尺寸锁死之后没有第二档了，七格直接并进来——用户实际用下来，
     * 165dp 的卡片放「状态 + 七格」正好，比原来空着半张卡有用。
     */
    static RemoteViews buildViews(Context ctx, WidgetModel m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_next_alarm);
        // v0.73.3：状态跟着实际走。原来写死「已开启」——闹钟全关时这张卡显示
        // 「已开启 + 暂无提醒」，自相矛盾。整月两张卡 v0.70.1 就接上了
        // Widgets.alarmStatusText，4×2 漏接，这次补上，几张卡一个口径。
        rv.setTextViewText(R.id.widget_status, Widgets.alarmStatusText(ctx));
        rv.setTextViewText(R.id.widget_hero, m.status);
        rv.setTextViewText(R.id.widget_chip, m.chip);
        rv.setTextViewText(R.id.widget_sub, m.sub);

        WeekWidgetRender.WeekModel week = WeekWidgetRender.computeModel(ctx);
        WeekWidgetRender.fillWeekCells(rv, ctx, week);

        // 点击整卡打开 App（v0.18.0）
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
     * <p>v0.68.0 起布局只有一份、尺寸由 resizeMode 锁死，理论上不会再触发。
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

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }
}
