package com.daxz.weekalarm;

import android.content.Context;
import android.widget.RemoteViews;

/**
 * 本月班表 · 紧凑（4×3，v1.51.1）。
 *
 * <p>4×4 那份用户反馈「有些浪费」：日历行高 28dp、上下留白多。
 * 4×3 把高度收到三行格子，行高自然压到 20~24dp，信息量一点没少。
 *
 * <p>闹钟放在标题行右侧（4×3 没有独立一行的余量，单开一条会挤掉一整行日历），
 * 设置里的「本月班表显示闹钟」控制显隐。
 */
public class MonthCompactWidget extends MonthWidgetBase {

    @Override
    int layoutRes() {
        return R.layout.widget_month_compact;
    }

    @Override
    boolean withActions() {
        return true;
    }

    static RemoteViews build(Context ctx) {
        return new MonthCompactWidget().buildViews(ctx);
    }

    public static void updateAll(Context ctx) {
        new MonthCompactWidget().push(ctx);
    }
}
