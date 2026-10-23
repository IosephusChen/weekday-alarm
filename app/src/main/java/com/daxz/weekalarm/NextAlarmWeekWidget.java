package com.daxz.weekalarm;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * 桌面小组件「下一个闹钟和本周」4×4（v1.50.0）。
 *
 * <p>v1.49.12 起，这份布局是「下一个闹钟」被拉到 4×4 时才出现的第二档；
 * 尺寸锁定之后不需要再猜高度——要闹钟 + 本周就直接选这个组件，
 * 只要闹钟就选 4×2 那个。两个组件各自固定尺寸，列表里一眼能看出区别。
 *
 * <p>内容与原来 4×3 / 4×4 那一档完全一致：闹钟两行 + 本周七格 + 「今天 / 明天不上班」。
 * 复用 {@link NextAlarmWidget#buildTall} 与 {@link WeekScheduleWidget#fillWeekCells}，
 * 没有第二套渲染逻辑。
 */
public class NextAlarmWeekWidget extends AppWidgetProvider {

    /**
     * 4×4 铺不下时的退路。
     *
     * <p>正常情况下 resizeMode="none" 会把尺寸钉死，这个分支永远不走。
     * 留着的理由：国产桌面是否遵守 resizeMode 还没在真机上验过（v1.50.0 的待验证项），
     * 万一它仍然允许拉小，宁可退回 4×2 那份布局，也不要让内容被裁掉半截。
     *
     * <p>判断用「h > 0 且偏小」而不是「h < 阈值」：部分启动器第一次绑定时
     * options 里没有高度，直接按 0 处理会把 4×4 误判成小尺寸、首次显示就是错的。
     */
    private static final int H_MIN = 220;

    private static boolean tooSmall(AppWidgetManager mgr, int id) {
        Bundle o = mgr == null ? null : mgr.getAppWidgetOptions(id);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return h > 0 && h < H_MIN;
    }

    static RemoteViews buildViews(Context ctx) {
        return NextAlarmWidget.buildTall(ctx, NextAlarmWidget.computeModel(ctx), true);
    }

    static RemoteViews buildFor(Context ctx, AppWidgetManager mgr, int id,
                                NextAlarmWidget.WidgetModel m) {
        return tooSmall(mgr, id)
                ? NextAlarmWidget.buildViews(ctx, m)
                : NextAlarmWidget.buildTall(ctx, m, true);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        NextAlarmWidget.WidgetModel m = NextAlarmWidget.computeModel(ctx);
        for (int id : ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id, m));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, Bundle newOptions) {
        if (mgr == null) return;
        mgr.updateAppWidget(id, buildFor(ctx, mgr, id, NextAlarmWidget.computeModel(ctx)));
    }

    public static void updateAll(Context ctx) {
        push(ctx, NextAlarmWidget.computeModel(ctx));
    }

    /** 复用主界面算好的「下一个闹钟 / 提醒总数」，省一次跨进程探测（同 v1.29.0）。 */
    public static void updateAll(Context ctx, ScheduleManager.NextAlarm next, int count) {
        push(ctx, NextAlarmWidget.computeModel(ctx, next, count));
    }

    private static void push(Context ctx, NextAlarmWidget.WidgetModel m) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NextAlarmWeekWidget.class));
        if (ids == null || ids.length == 0) return;   // 桌面没放：一次模型都不算
        for (int id : ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id, m));
    }
}
