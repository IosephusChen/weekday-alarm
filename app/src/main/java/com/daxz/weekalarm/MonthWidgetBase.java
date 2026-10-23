package com.daxz.weekalarm;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.time.LocalDate;

/**
 * 三个「本月班表」变体的共同实现（v1.51.1）。
 *
 * <p>为什么有变体：4×4 用户反馈「有些浪费」——一个月的日历用不着四行格子那么高。
 * 但「多高才合适」是主观的，所以先做三个版本让用户实际用一段时间再决定：
 *
 * <table>
 *   <tr><th>变体</th><th>尺寸</th><th>按钮</th></tr>
 *   <tr><td>{@link MonthScheduleWidget}</td><td>4×4</td><td>有，闹钟独立一行</td></tr>
 *   <tr><td>{@link MonthCompactWidget}</td><td>4×3</td><td>有，闹钟在标题行右侧</td></tr>
 *   <tr><td>{@link MonthMinimalWidget}</td><td>4×3</td><td>无</td></tr>
 * </table>
 *
 * <p>三个变体只在这两处不同，42 格的算与画全部走 {@link MonthWidgetRender}。
 * 试用期结束后删掉落选的两个，这个基类跟着删。
 */
abstract class MonthWidgetBase extends AppWidgetProvider {

    /** 这个变体用哪份布局。 */
    abstract int layoutRes();

    /** 要不要底部「今天 / 明天不上班」。 */
    abstract boolean withActions();

    /**
     * 尺寸兜底：真锁不住、被压到 4×2 高度时，宁可退回「本周班表」，
     * 也不让六行日历被裁掉半截。
     *
     * <p>阈值取 180：4×3 是 206dp、4×4 是 280dp，都在这之上；
     * 低于 180 说明宿主没按 targetCell 给高度。
     */
    private static final int H_MIN = 180;

    private static boolean tooSmall(AppWidgetManager mgr, int id) {
        Bundle o = mgr == null ? null : mgr.getAppWidgetOptions(id);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return h > 0 && h < H_MIN;
    }

    RemoteViews buildViews(Context ctx) {
        return MonthWidgetRender.build(ctx, MonthWidgetRender.compute(ctx, LocalDate.now()),
                layoutRes(), withActions(), Prefs.isMonthWidgetAlarm(ctx));
    }

    RemoteViews buildFor(Context ctx, AppWidgetManager mgr, int id) {
        if (tooSmall(mgr, id)) {
            return WeekScheduleWidget.buildViews(ctx, WeekScheduleWidget.computeModel(ctx));
        }
        return buildViews(ctx);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, Bundle newOptions) {
        if (mgr == null) return;
        mgr.updateAppWidget(id, buildFor(ctx, mgr, id));
    }

    /** 推给「自己这个子类」在桌面上的所有实例。 */
    void push(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, getClass()));
        if (ids == null || ids.length == 0) return;   // 桌面没放：一次模型都不算
        for (int id : ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id));
    }
}
