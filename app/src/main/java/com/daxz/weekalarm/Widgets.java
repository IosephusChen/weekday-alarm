package com.daxz.weekalarm;

import android.content.Context;

/** 桌面小组件的统一刷新入口（v1.20）：主 App 只调这一个方法，避免漏刷某一个组件。 */
public final class Widgets {

    private Widgets() {
    }

    public static void updateAll(Context ctx) {
        if (ctx == null) return;
        try {
            NextAlarmWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
        try {
            WeekScheduleWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
    }
}
