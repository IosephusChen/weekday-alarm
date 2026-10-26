package com.daxz.weekalarm;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
     * 午夜刷新（v1.48.0）：跨过 00:00 时把文案里的「明天」改成「今天」。
     *
     * <p>只在这一条通知还挂着时才由 {@link BedtimeReminder#scheduleMidnightRefresh} 排出来，
     * 触发一次即结束——第二天晚上发新提醒时会重新排。
     */
    public static final String ACTION_MIDNIGHT = "com.daxz.weekalarm.BEDTIME_MIDNIGHT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SKIP_TOMORROW.equals(action)) {
            skipTomorrow(context, intent);
            return;
        }
        if (ACTION_MIDNIGHT.equals(action)) {
            // 跨天了：昨晚的「明天」就是今天，重算一次让文案改口（不再是「明天」）
            BedtimeReminder.refreshIfShown(context);
            return;
        }
        if (!ACTION_BEDTIME.equals(action)) return;

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BedtimeReminder.Model m = BedtimeReminder.computeModel(context, tomorrow);
        if (m.notify) {
            showNotification(context, m, false);
        }
        // 无论发没发，都要续订下一天：今天没发（明天休息）不代表明天不发
        BedtimeReminder.reschedule(context);
    }

    /**
     * 重发同一条提醒，只换内容不再响（v1.46.7）。供 {@link BedtimeReminder#refreshIfShown} 调用：
     * 用户把「明天没有闹钟」那条改成了真闹钟，通知栏上那句话得跟着变，但没必要再提醒一次。
     */
    static void repost(Context ctx, BedtimeReminder.Model m) {
        showNotification(ctx, m, true);
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS 可能被用户拒绝；下面的 try-catch 兜底
    private static void showNotification(Context ctx, BedtimeReminder.Model m, boolean quiet) {
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(ctx, App.CHANNEL_BEDTIME)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(m.title)
                .setContentText(m.text)
                .setContentIntent(contentPi)
                // v1.37.0：强调色统一成品牌紫，和「即将响铃」那条看起来是一家的
                .setColor(ctx.getColor(R.color.indigo))
                .setAutoCancel(true)
                // quiet=true 时只更新内容：同 id 重发不会再次弹横幅 / 响铃
                .setOnlyAlertOnce(quiet)
                .setPriority(Notification.PRIORITY_DEFAULT);

        // 「请假」按钮的文案跟着目标日期走（v1.58.0 修复）：
        // 跨午夜后这条通知说的已是「今天早上 07:30」（v1.48.0 的改口），按钮再写
        // 「明天请假」就会标错一天——用户想拦今早的闹钟，标的却是明天，今早照响。
        // data URI 一直带着真实目标日期（showNotification 里按 m.tomorrow 生成），
        // 这里从 URI 解析；解析不了才回退「明天」（旧版本烧死的 PendingIntent 没有日期）。
        boolean canSkip = m.workday && !m.vacation;
        String dayWord = m.tomorrow.isAfter(LocalDate.now()) ? "明天" : "今天";
        PendingIntent skipPi = null;
        if (canSkip) {
            Intent skip = new Intent(ctx, BedtimeReceiver.class)
                    .setAction(ACTION_SKIP_TOMORROW)
                    .setData(android.net.Uri.parse("daxz://bedtime/skip/" + m.tomorrow));
            skipPi = PendingIntent.getBroadcast(ctx, 12, skip,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif_calendar),
                    dayWord + "请假", skipPi).build());
        }
        // v1.38.0：折叠态换自绘卡片。
        //
        // 设置里写着「可一键标记请假」，但 addAction 的按钮只在**展开后**出现——
        // 锁屏和刚弹出来时用户看到的是一张没有按钮的卡片，等于这句话没兑现。
        // 和提前提醒（v1.23.6）、响铃（本版）一样把按钮画进布局里，说了就要能点。
        nb.setCustomContentView(NotifCard.build(ctx, m.title, m.text, 0, contentPi,
                canSkip ? NotifCard.Button.primary(dayWord + "请假", skipPi) : null));
        // v1.47.9：这条提醒只对「今晚」有效。用户不点也不划，它会一直挂到第二天白天，
        // 那时内容已经错了——写着「明天 07:30 起床」，其实说的是今天早上。
        // 让它自带过期时间：到次日 05:00 由系统移除，App 从没被打开也一样。
        nb.setTimeoutAfter(millisUntilNextMorning());
        try {
            NotificationManagerCompat.from(ctx).notify(BedtimeReminder.NOTIF_ID, nb.build());
            // 记下「这条属于哪一天」，App 下次打开时据此判断是不是昨天的（refreshIfShown）
            Prefs.setBedtimeNoticeDay(ctx, LocalDate.now().toString());
            // 00:00 一到就改口：这段时间用户在睡觉，不会打开 App 触发刷新
            BedtimeReminder.scheduleMidnightRefresh(ctx);
        } catch (Exception ignore) {
        }
    }

    /**
     * 一键「请假」：把通知指向的那天标为个人请假，取消那天已排的全部闹钟。
     *
     * <p><b>目标日期从 intent 的 data URI 取，不再硬编码「明天」</b>（v1.58.0 修复）：
     * 跨午夜后 v1.48.0 的午夜刷新会把通知改口成「今天早上 07:30」，按钮也跟着变成
     * 「今天请假」——此时点的就是今天。旧实现写死 {@code now + 1}，凌晨点下去标的是
     * 明天，今早的闹钟照响，按钮与行为对不上。
     *
     * <p>解析失败（旧版本烧死的 PendingIntent 没有日期）才回退「明天」。
     *
     * <p>重排是上百次 IPC，不能占着广播主线程（10 秒上限），因此走 goAsync + 后台线程；
     * 界面上的 Toast 先给，用户立刻有反馈。
     */
    private void skipTomorrow(Context ctx, Intent intent) {
        LocalDate target = LocalDate.now().plusDays(1);
        try {
            if (intent != null && intent.getData() != null) {
                String last = intent.getData().getLastPathSegment();
                if (last != null) target = LocalDate.parse(last);
            }
        } catch (Exception ignore) {
            // 解析不了就按「明天」——旧 PendingIntent 的容错
        }
        Prefs.addVacation(ctx, target);
        try {
            NotificationManagerCompat.from(ctx).cancel(BedtimeReminder.NOTIF_ID);
        } catch (Exception ignore) {
        }
        try {
            Toast.makeText(ctx, "已把" + (target.isAfter(LocalDate.now()) ? "明天" : "今天")
                    + "标记为请假，闹钟不会响", Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
        // v1.46.7：同上——用户正看着 App 时点了通知，界面必须当场变
        UiRefresh.request(ctx);
        final PendingResult pending = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                ScheduleManager.rescheduleAll(app);
                Widgets.updateAll(app);
            } catch (Throwable ignore) {
            } finally {
                if (pending != null) pending.finish();   // 直接调用（测试）时为 null
            }
        }, "bedtime-skip").start();
    }

    /**
     * 距次日 05:00 还有多少毫秒（v1.47.9）。
     *
     * <p>为什么是 05:00：那是这一夜结束、起床闹钟开始响的时段，再往后这条提醒就没有意义了。
     * 下限给 1 小时——极端情况（用户把提醒设在凌晨 4 点多）也不会刚发出就消失。
     *
     * <p>包级可见便于单测：过期时间是这条通知唯一的「自清理」依据。
     */
    static long millisUntilNextMorning() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expire = now.toLocalDate().plusDays(1).atTime(5, 0);
        long ms = Duration.between(now, expire).toMillis();
        return Math.max(ms, 60L * 60 * 1000);
    }
}
