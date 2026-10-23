package com.daxz.weekalarm;

import android.content.Context;

/**
 * 桌面小组件的统一刷新入口（v1.20）：主 App 只调这一个方法，避免漏刷某一个组件。
 *
 * <p>v1.53.0 起是三个（4×2 闹钟 / 本月班表 4×4 完整 / 本月班表 4×3 极简），
 * 这里按「一个都不漏」的原则全部列出来。每个调用各自 try-catch：
 * 某个组件的数据算炸了，不能连带其它组件一起停刷。
 */
public final class Widgets {

    private Widgets() {
    }

    /**
     * 组件标题行右侧那句状态（v1.52.0）：闹钟到底开着没有。
     *
     * <p>v1.51.x 的整月卡在这里写「10月24日 周六 07:30」——整月日历本来就在看日期，
     * 再报一遍下一个闹钟的具体时刻，用户反馈「多余的信息太多」。改成一句话：
     * 要看几点几分，4×2 的闹钟卡上更清楚。
     *
     * <p>读原始列表而不是 {@code getAlarmsSettled}：后者会顺手结算过期的一次性闹钟、
     * 可能写盘，而组件刷新是高频动作，不该带副作用。
     */
    static String alarmStatusText(Context ctx) {
        for (Alarm a : Prefs.getAlarms(ctx)) {
            if (a.enabled) return "闹钟已开启";
        }
        return "闹钟未开启";
    }

    public static void updateAll(Context ctx) {
        if (ctx == null) return;
        try {
            NextAlarmWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
        try {
            MonthScheduleWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
        try {
            MonthMinimalWidget.updateAll(ctx);
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
            MonthScheduleWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
        try {
            MonthMinimalWidget.updateAll(ctx);
        } catch (Throwable ignore) {
        }
    }
}
