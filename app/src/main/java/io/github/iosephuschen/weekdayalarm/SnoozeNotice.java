package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 「稍后提醒」期间挂在通知栏的那条提醒（v0.53.1）。
 *
 * <p>为什么要有它：点「5 分钟后再响」之后，响铃服务停掉、响铃通知撤掉，
 * 通知栏就空了一块——用户看不到「它还会不会响、什么时候响」，
 * 只能干等到铃声再炸一次。这中间本来是最需要确认的时刻。
 *
 * <p>做法：小睡排程排好之后，立刻发一条安静的通知（v0.55.4 起走独立的「稍后提醒」渠道，
 * 不响不震、不横幅），写清几点再响，并挂一个系统倒计时。真响的时候由
 * {@link AlarmService#onStartCommand} 撤掉它，不会两条并存。
 *
 * <p>id 段用 4000+，避开提前提醒的 2000–2999：
 * {@link ScheduleManager#dropPreNoticesExcept} 每次重排都会扫那个区间做对账，
 * 撞进去会被误删。
 */
final class SnoozeNotice {

    static final int NOTIF_ID_BASE = 4000;

    /** 通知里「取消」按钮的 action，由 {@link SnoozeReceiver} 处理。 */
    static final String ACTION_CANCEL = "io.github.iosephuschen.weekdayalarm.CANCEL_SNOOZE";

    private SnoozeNotice() {
    }

    static int id(int alarmId) {
        return NOTIF_ID_BASE + alarmId;
    }

    /**
     * 发一条「N 分钟后再次响铃」。
     *
     * <p>必须在小睡闹钟已经排好之后调用：这条通知承诺「还会响」，而真正兑现承诺的是
     * {@link ScheduleManager#snooze} 注册的那个闹钟。排程失败时发这条通知就是骗人。
     */
    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS 可能被用户拒绝；下面的 try-catch 兜底
    static void post(Context ctx, int alarmId, String label, int minutes) {
        int mins = Math.max(1, minutes);
        LocalDateTime at = LocalDateTime.now().plusMinutes(mins);
        LocalDate targetDate = at.toLocalDate();
        String clock = TimeText.clock(at);
        String name = (label == null || label.isEmpty()) ? "闹钟" : label;

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, 41 + alarmId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancel = new Intent(ctx, SnoozeReceiver.class)
                .setAction(ACTION_CANCEL)
                .setData(Uri.parse("weekdayalarm://snooze/cancel/" + alarmId + "/" + targetDate))
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(SnoozeReceiver.EXTRA_DATE, targetDate.toString())
                .putExtra(AlarmReceiver.EXTRA_LABEL, name);
        PendingIntent cancelPi = PendingIntent.getBroadcast(ctx, 42 + alarmId, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAt = at.atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        Notification.Builder nb = new Notification.Builder(ctx, App.CHANNEL_SNOOZE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name + " 稍后再响")
                .setContentText(mins + " 分钟后 · " + clock + " 再次响铃")
                .setContentIntent(openPi)
                .setColor(ctx.getColor(R.color.indigo))
                .setCategory(Notification.CATEGORY_ALARM)
                // 渠道已经是 LOW，这里再写 HIGH 只会误导下一个读代码的人
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 可以划掉：它只是一条告知，划掉不代表取消小睡
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setWhen(triggerAt)
                .setShowWhen(true)
                .setCustomContentView(NotifCard.build(ctx, name + " 稍后再响",
                        mins + " 分钟后 · " + clock + " 再次响铃", triggerAt, openPi,
                        NotifCard.Button.secondary("取消", cancelPi)));
        try {
            NotificationManagerCompat.from(ctx).notify(id(alarmId), nb.build());
        } catch (Exception ignore) {
        }
    }

    /** 撤掉这条提醒。响铃开始、用户停止、取消小睡时都要调，避免它一直挂着。 */
    static void cancel(Context ctx, int alarmId) {
        try {
            NotificationManagerCompat.from(ctx).cancel(id(alarmId));
        } catch (Exception ignore) {
        }
    }

    /** 取消小睡时的提示：告诉用户「不会再响」，否则他还会等。 */
    static void toastCancelled(Context ctx) {
        try {
            Toast.makeText(ctx, "已取消这次稍后提醒", Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
    }
}
