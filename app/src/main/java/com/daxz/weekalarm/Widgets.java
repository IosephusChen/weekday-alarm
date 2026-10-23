package com.daxz.weekalarm;

import android.content.Context;

/**
 * 桌面小组件的统一刷新入口（v1.20）：主 App 只调这一个方法，避免漏刷某一个组件。
 *
 * <p>v1.51.0 起是三个（4×2 闹钟 / 4×2 本周 / 4×4 本月），这里按「一个都不漏」的原则
 * 全部列出来。每个调用各自 try-catch：某个组件的数据算炸了，不能连带另外两个一起停刷。
 */
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
        try {
            MonthScheduleWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 同上，但复用主界面刚算好的「下一个闹钟 / 提醒总数」（v1.29.0）。
     *
     * <p>首页刷新时这两样本来就算过一次，而 {@code nextRegisteredAlarm} 是逐候选
     * PendingIntent 探测（跨进程 IPC）。小组件再算一遍，等于每次刷新多付一次同样的钱。
     * 桌面没放小组件时 {@code getAppWidgetIds} 为空、本来就跳过，所以收益只落在
     * 「真的放了小组件」的用户身上——而那正是最在意这个 App 的人。
     *
     * <p>只有「闹钟」组件吃这份结果；两个班表组件算的是另一套东西，照旧自己算。
     */
    public static void updateAll(Context ctx, ScheduleManager.NextAlarm next, int count) {
        if (ctx == null) return;
        try {
            NextAlarmWidget.updateAll(ctx, next, count);
        } catch (Throwable ignore) {
        }
        try {
            WeekScheduleWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
        try {
            MonthScheduleWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
    }
}
