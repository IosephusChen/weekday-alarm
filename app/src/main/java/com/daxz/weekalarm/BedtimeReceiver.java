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

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SKIP_TOMORROW.equals(action)) {
            skipTomorrow(context);
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
