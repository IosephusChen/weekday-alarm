package com.daxz.weekalarm;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.time.LocalDate;

/**
 * 桌面小组件「本月班表」4×4（v1.50.0）。
 *
 * <p>原来这是「本周班表」被拉到 4×4 时才出现的整月布局（v1.49.10）。
 * 尺寸锁定之后独立成一个组件：想看整月就选它，想看这周就选 4×2 的「本周班表」。
 *
 * <p>渲染完全交给 {@link MonthWidgetRender}，与 v1.49.10 那一档逐像素一致。
 */
public class MonthScheduleWidget extends AppWidgetProvider {

    /** 尺寸兜底：真锁不住、被拉到 4×2 高度时，宁可隐藏内容也不让日历被裁半截。 */
    private static final int H_MIN = 220;

    private static boolean tooSmall(AppWidgetManager mgr, int id) {
        Bundle o = mgr == null ? null : mgr.getAppWidgetOptions(id);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return h > 0 && h < H_MIN;
    }

    static RemoteViews buildViews(Context ctx) {
        return MonthWidgetRender.build(ctx, MonthWidgetRender.compute(ctx, LocalDate.now()));
    }

    static RemoteViews buildFor(Context ctx, AppWidgetManager mgr, int id) {
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

    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, MonthScheduleWidget.class));
        if (ids == null || ids.length == 0) return;
        for (int id : ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id));
    }
}
