package com.daxz.weekalarm;

import android.content.Context;
import android.widget.RemoteViews;

/**
 * 本月班表 · 极简（4×3，v0.69.0）。
 *
 * <p>和 4×4 同一份布局，只是不显示底部信息行——省下的高度全给日历行，
 * 格子更高、日期更好认。200dp 高度下 42 格已经排到极限（v0.77.3 刚修完压字），
 * 再加一行会把字又压回去。
 *
 * <p>为什么不复制一份布局：42 格的 XML 有 1800 行，为「有没有底部那一行」复制一遍，
 * 以后改颜色、改字号要改两处，迟早改漏一处。
 */
public class MonthMinimalWidget extends MonthWidgetBase {

    @Override
    int layoutRes() {
        return R.layout.widget_month_compact;
    }

    @Override
    boolean withFooter() {
        return false;
    }

    static RemoteViews build(Context ctx) {
        return new MonthMinimalWidget().buildViews(ctx);
    }

    public static void updateAll(Context ctx) {
        new MonthMinimalWidget().push(ctx);
    }
}
