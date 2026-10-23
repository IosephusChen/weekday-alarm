package com.daxz.weekalarm;

import android.content.Context;
import android.widget.RemoteViews;

/**
 * 本月班表 · 极简（4×3，v1.51.1）。
 *
 * <p>和「紧凑」同一份布局，只是不显示底部两个请假按钮——省下 24dp 全给日历行，
 * 格子更高、日期更好认。请假走「点卡片进 App」，不是没有入口。
 *
 * <p>为什么不复制一份布局：42 格的 XML 有 1700 行，为「有没有按钮」复制一遍，
 * 以后改颜色、改字号要改两处，迟早改漏一处。
 */
public class MonthMinimalWidget extends MonthWidgetBase {

    @Override
    int layoutRes() {
        return R.layout.widget_month_compact;
    }

    @Override
    boolean withActions() {
        return false;
    }

    static RemoteViews build(Context ctx) {
        return new MonthMinimalWidget().buildViews(ctx);
    }

    public static void updateAll(Context ctx) {
        new MonthMinimalWidget().push(ctx);
    }
}
