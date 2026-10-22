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

    /**
     * 小组件按钮的通用版（v1.33）：带一个 {@link #EXTRA_DAY_OFFSET}（0 = 今天，1 = 明天）。
     *
     * <p>为什么不再加一个 ACTION_TOGGLE_TODAY：两个按钮的逻辑一模一样，只有日期差一天；
     * 复制一份等于给以后埋一个「改了一处忘了另一处」的坑。旧 action 保留，是因为
     * 1.31 那版组件实例的 PendingIntent 里还烧着它——升级后不认，用户点旧组件就没反应。
     */
    public static final String ACTION_TOGGLE_DAY = "com.daxz.weekalarm.TOGGLE_DAY";
    public static final String EXTRA_DAY_OFFSET = "day_offset";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SKIP_TOMORROW.equals(action)) {
            skipTomorrow(context);
            return;
        }
        if (ACTION_TOGGLE_TOMORROW.equals(action)) {
            toggleDay(context, 1);
            return;
        }
        if (ACTION_TOGGLE_DAY.equals(action)) {
            toggleDay(context, intent.getIntExtra(EXTRA_DAY_OFFSET, 1));
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

        // 「明天请假」只在「明天真要上班」时给：休息日/已请假时这个按钮没有意义
        boolean canSkip = m.workday && !m.vacation;
        PendingIntent skipPi = null;
        if (canSkip) {
            Intent skip = new Intent(ctx, BedtimeReceiver.class)
                    .setAction(ACTION_SKIP_TOMORROW)
                    .setData(android.net.Uri.parse("daxz://bedtime/skip/" + m.tomorrow));
            skipPi = PendingIntent.getBroadcast(ctx, 12, skip,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif_calendar),
                    "明天请假", skipPi).build());
        }
        // v1.38.0：折叠态换自绘卡片。
        //
        // 设置里写着「可一键标记请假」，但 addAction 的按钮只在**展开后**出现——
        // 锁屏和刚弹出来时用户看到的是一张没有按钮的卡片，等于这句话没兑现。
        // 和提前提醒（v1.23.6）、响铃（本版）一样把按钮画进布局里，说了就要能点。
        nb.setCustomContentView(NotifCard.build(ctx, m.title, m.text, 0, contentPi,
                canSkip ? NotifCard.Button.primary("明天请假", skipPi) : null));
        try {
            NotificationManagerCompat.from(ctx).notify(BedtimeReminder.NOTIF_ID, nb.build());
        } catch (Exception ignore) {
        }
    }

    /**
     * 小组件上的「今天 / 明天不上班」：按那一天的实际类型来回切（v1.31，v1.33 起支持今天）。
     *
     * <p>v1.34 起语义只剩一份实现（{@link DayOffAction}），这里只负责广播场景该做的事：
     * 先弹提示，再 goAsync 到后台重排——重排是上百次 IPC，不能占着广播主线程。
     */
    private void toggleDay(Context ctx, int dayOffset) {
        String msg = DayOffAction.toggle(ctx, dayOffset);
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
        // v1.46.7：App 可能正开着，改完得让它立刻刷新，不能等用户退到桌面再进来；
        // 通知栏上那条睡前提醒也可能正说着「明天要上班」，一并改口
        UiRefresh.request(ctx);
        BedtimeReminder.refreshIfShown(ctx);
        // 重排是上百次 IPC，不能占着广播主线程（10 秒上限）——与 skipTomorrow 同一套做法
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
        }, "widget-toggle-day").start();
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
}
