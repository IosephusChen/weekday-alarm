package com.daxz.weekalarm;

import android.content.Context;
import android.widget.RemoteViews;

/**
 * 本月班表 · 完整（4×4，v1.51.1）。
 *
 * <p>原来这是「本周班表」被拉到 4×4 时才出现的整月布局（v1.49.10），
 * v1.50.0 独立成组件。v1.51.1 加了「下一个闹钟」一行（设置里可关），
 * 和另外两个 4×3 变体一起试用。
 *
 * <p>渲染交给 {@link MonthWidgetRender}，三个变体共用。
 */
public class MonthScheduleWidget extends MonthWidgetBase {

    @Override
    int layoutRes() {
        return R.layout.widget_week_schedule_month;
    }

    @Override
    boolean withActions() {
        return true;
    }

    /** 给测试用的静态入口（父类同名方法是实例方法，子类不能再定义静态版）。 */
    static RemoteViews build(Context ctx) {
        return new MonthScheduleWidget().buildViews(ctx);
    }

    public static void updateAll(Context ctx) {
        new MonthScheduleWidget().push(ctx);
    }
}
