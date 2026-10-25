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
        // v1.71.4：顺带把「跨日刷新」挂上。放在这里而不是每个调用点——
        // 组件的所有刷新入口（开 App、开机、改设置、导入、闹钟变化）都会走到这，
        // 漏一个就有一类设备在零点后停在昨天。
        WidgetTicker.scheduleNext(ctx);
    }
}
