package com.daxz.weekalarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 桌面小组件的「跨日刷新」（v1.71.4）。
 *
 * <p>三个组件的内容都跟「今天」走：4×2 的今天状态与七格高亮、两张整月卡的今天格子、
 * 「下次 明天 07:00」的措辞。系统给 {@code updatePeriodMillis} 的最小周期是 30 分钟，
 * 零点过后组件最多会带着昨天的状态显示半小时——用户 00:07 截图反馈
 * 「已经周一了，小部件还显示周日」，就是这么来的。
 *
 * <p>所以在每天 00:00:30 挂一条闹钟：触发时刷新三个组件并挂下一天。
 * 用 {@code setExactAndAllowWhileIdle}（与提前提醒同一档）：打盹模式下也能在一分钟内到；
 * 没有精确权限时退回 {@code setWindow(60s)}，最坏延迟到下一个维护窗口，但不会丢。
 *
 * <p>为什么不用 {@code ACTION_DATE_CHANGED}：那是受保护广播，静态注册收不到；
 * 动态注册又要求进程常驻，为一个每日刷新养个服务不值。
 */
final class WidgetTicker {

    /** 每天几点刷新：零点过 30 秒，给系统跨日留一点余量。 */
    static final LocalTime TICK_AT = LocalTime.of(0, 0, 30);

    private static final int REQUEST_CODE = 0x5701;

    private WidgetTicker() {
    }

    /**
     * 下一次刷新的时刻（epoch 毫秒）：今天 00:00:30 还没到就是今天，否则明天。
     *
     * <p>纯函数（时间与时区都从参数进），便于单测——跨零点、跨日这两个边界
     * 靠真机等是等不到的。
     */
    static long nextTickMillis(long nowMillis, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        LocalDate day = now.toLocalDate();
        ZonedDateTime today = day.atTime(TICK_AT).atZone(zone);
        ZonedDateTime next = now.isBefore(today)
                ? today
                : day.plusDays(1).atTime(TICK_AT).atZone(zone);
        return next.toInstant().toEpochMilli();
    }

    /** 挂下一次跨日刷新（重复调用只是覆盖同一条，幂等）。 */
    static void scheduleNext(Context ctx) {
        if (ctx == null) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE,
                    new Intent(ctx, WidgetTickReceiver.class)
                            .setAction(WidgetTickReceiver.ACTION_TICK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = nextTickMillis(System.currentTimeMillis(), ZoneId.systemDefault());
            if (ScheduleManager.canScheduleExactAlarms(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
            }
        } catch (Throwable ignore) {
            // 挂不上就算了：系统的 30 分钟周期更新仍然会兜底，只是慢一点
        }
    }
}
