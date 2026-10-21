package com.daxz.weekalarm;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;

/**
 * 睡前提醒的接收器（v1.20）：到点发通知，并顺手把「明天请假」做掉。
 *
 * 触发源只有一条：{@link BedtimeReminder#reschedule} 注册的固定 PendingIntent。
 * 每次触发后立即续订下一天——这是它唯一的续命机制，漏了这一步提醒就只发一次。
 */
public class BedtimeReceiver extends BroadcastReceiver {

    public static final String ACTION_BEDTIME = "com.daxz.weekalarm.BEDTIME";
    public static final String ACTION_SKIP_TOMORROW = "com.daxz.weekalarm.SKIP_TOMORROW";
    /**
     * 桌面小组件的「明天不上班 / 明天要加班」按钮（v1.31）。
     *
     * <p>与 {@link #ACTION_SKIP_TOMORROW} 的区别：那个只能单向请假（通知里的动作，
     * 点完通知就没了）；小组件上的按钮一直在，必须是可来回切的开关，
     * 否则点错一次就只能进 App 改回来。
     */
    public static final String ACTION_TOGGLE_TOMORROW = "com.daxz.weekalarm.TOGGLE_TOMORROW";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SKIP_TOMORROW.equals(action)) {
            skipTomorrow(context);
            return;
        }
        if (ACTION_TOGGLE_TOMORROW.equals(action)) {
            toggleTomorrow(context);
            return;
        }
        if (!ACTION_BEDTIME.equals(action)) return;

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BedtimeReminder.Model m = BedtimeReminder.computeModel(context, tomorrow);
        if (m.notify) {
            showNotification(context, m);
        }
        // 无论发没发，都要续订下一天：今天没发（明天休息）不代表明天不发
        BedtimeReminder.reschedule(context);
    }

    private void showNotification(Context ctx, BedtimeReminder.Model m) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(ctx, App.CHANNEL_BEDTIME)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(m.title)
                .setContentText(m.text)
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_DEFAULT);

        // 「明天请假」只在「明天真要上班」时给：休息日/已请假时这个按钮没有意义
        if (m.workday && !m.vacation) {
            Intent skip = new Intent(ctx, BedtimeReceiver.class)
                    .setAction(ACTION_SKIP_TOMORROW)
                    .setData(android.net.Uri.parse("daxz://bedtime/skip/" + m.tomorrow));
            PendingIntent skipPi = PendingIntent.getBroadcast(ctx, 12, skip,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif_calendar),
                    "明天请假", skipPi).build());
        }
        try {
            NotificationManagerCompat.from(ctx).notify(BedtimeReminder.NOTIF_ID, nb.build());
        } catch (Exception ignore) {
        }
    }

    /**
     * 小组件上的「明天不上班 / 明天要加班」：按明天的实际类型来回切（v1.31）。
     *
     * <p>语义跟主界面今日卡那个按钮完全一致：上班 / 补班日 → 请假；休息 / 法定假 →
     * 加班。两边改的是同一份 Prefs，所以点完回 App 看，日历与列表都是新的。
     */
    private void toggleTomorrow(Context ctx) {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        WeekSchedule.DayType type = WeekSchedule.dayType(tomorrow, Prefs.getRule(ctx));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
        String msg;
        if (work) {
            if (Prefs.isVacation(ctx, tomorrow)) {
                Prefs.removeVacation(ctx, tomorrow);
                msg = "明天恢复上班，闹钟照常";
            } else {
                Prefs.addVacation(ctx, tomorrow);
                msg = "已把明天标记为请假，闹钟不会响";
            }
        } else {
            if (Prefs.isOvertime(ctx, tomorrow)) {
                Prefs.removeOvertime(ctx, tomorrow);
                msg = "明天恢复休息，不响闹钟";
            } else {
                Prefs.addOvertime(ctx, tomorrow);
                msg = "已把明天标记为加班，「仅上班日」闹钟照响";
            }
        }
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
        // 重排是上百次 IPC，不能占着广播主线程（10 秒上限）——与 skipTomorrow 同一套做法
        final PendingResult pending = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                ScheduleManager.rescheduleAll(app);
                Widgets.updateAll(app);
            } catch (Throwable ignore) {
            } finally {
                pending.finish();
            }
        }, "widget-toggle-tomorrow").start();
    }

    /**
     * 一键「明天请假」：把明天标为个人请假，取消明天已排的全部闹钟。
     *
     * 重排是上百次 IPC，不能占着广播主线程（10 秒上限），因此走 goAsync + 后台线程；
     * 界面上的 Toast 先给，用户立刻有反馈。
     */
    private void skipTomorrow(Context ctx) {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Prefs.addVacation(ctx, tomorrow);
        try {
            NotificationManagerCompat.from(ctx).cancel(BedtimeReminder.NOTIF_ID);
        } catch (Exception ignore) {
        }
        try {
            Toast.makeText(ctx, "已把明天标记为请假，闹钟不会响", Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
        final PendingResult pending = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                ScheduleManager.rescheduleAll(app);
                Widgets.updateAll(app);
            } catch (Throwable ignore) {
            } finally {
                pending.finish();
            }
        }, "bedtime-skip").start();
    }
}
