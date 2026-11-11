package io.github.iosephuschen.weekdayalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** 闹钟触发：直接弹出全屏界面 + 启动前台服务响铃 + 全屏通知兜底，并补齐后续窗口的闹钟。 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_LABEL = "alarm_label";

    /**
     * 这一轮响铃的起始日期（v0.91.9）。只有小睡触发的那几次会带上：
     * 23:58 响 → 小睡到 00:03 时，URI 里的日期已经是新的一天，但「这一轮用了几次小睡」
     * 必须记在起始那天，否则次数归零、可以无限续睡。
     */
    public static final String EXTRA_SNOOZE_ORIGIN = "snooze_origin";

    /** 时钟跳变后的重排节流水位：一次跳变会连着来几百条陈旧广播，只让第一条去排。 */
    private static long lastJumpRescheduleAt;

    /**
     * 这一条是不是「时钟被大幅拨动后，从过去捞出来的」旧实例（v1.23.30）。
     *
     * <p>AlarmManager 的 RTC 闹钟一旦触发时刻落到过去，时间一跳就会被立即派发。
     * 我们给 90 天窗口里的每一天都注册了实例，把日期跳到三个月后等于一次派发几百个。
     *
     * <p>判据是「日期差 &gt; 1 天」：正常实例的 date 就是今天；跨午夜的 Doze 延迟
     * 最多差一天（23:59 的闹钟 00:30 才响），再宽就会把真的补响一起吞掉。
     * 日期解析不出来时一律当正常实例——宁可多响一次，也不能吞掉真闹钟。
     */
    private static boolean isStale(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return false;
        try {
            return java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(dateStr), java.time.LocalDate.now()) > 1;
        } catch (Exception notADate) {
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // data URI 形如 weekdayalarm://alarm/{alarmId}/{date}；lastPathSegment 是日期
        String dateStr = null;
        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 0);
        String label = intent.getStringExtra(EXTRA_LABEL);
        if (label == null) label = "闹钟";
        if (intent.getData() != null) {
            dateStr = intent.getData().getLastPathSegment();
        }
        if (isStale(dateStr)) {
            // v1.23.30：时钟被大幅拨动后，系统会把 90 天窗口里所有落到过去的实例
            // 一次性派发过来，几百条连着进——每一条都拉全屏、起前台服务，
            // 听上去就是闹钟狂响（用户 2026-11-11 07:0x 报的就是这个）。
            // 这些不是「迟到的闹钟」，是跳变的副产物，直接丢掉。
            // 但系统里现存的实例已经全部过期，得重排一次；几百条广播只让第一条去排。
            boolean firstAfterJump = false;
            synchronized (AlarmReceiver.class) {
                long now = System.currentTimeMillis();
                if (now - lastJumpRescheduleAt > 5000) {
                    lastJumpRescheduleAt = now;
                    firstAfterJump = true;
                }
            }
            if (firstAfterJump) {
                final PendingResult pending = goAsync();
                final Context app = context.getApplicationContext();
                new Thread(() -> {
                    try {
                        // v1.23.31：走守卫。这里失败最要命——旧实例已全部过期，
                        // 这次没排上就是零实例，而且没有下一次响铃来「顺带补」。
                        RescheduleGuard.runTracked(app);
                    } finally {
                        pending.finish();
                    }
                }, "clock-jump-reschedule").start();
            }
            return;
        }
        // 锁屏 / 息屏：直接拉整屏（那时候通知和小窗都没人看得见）。
        // 解锁使用中：不在这里拉界面，交给 AlarmService 弹一个 300dp 的小悬浮窗——
        // v0.53.2 试过「解锁也整屏」，用户反馈太粗暴：正在用手机时被整屏盖住，
        // 要的是「小窗提醒一下」。挂不上悬浮窗时服务自己退回整屏。
        final String origin = intent.getStringExtra(EXTRA_SNOOZE_ORIGIN);
        final boolean popup = ScreenLock.shouldPopup(context);
        if (popup) {
            try {
                Intent full = new Intent(context, AlarmActivity.class);
                full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
                full.putExtra(AlarmActivity.EXTRA_TEST, false);
                full.putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId);
                full.putExtra(AlarmActivity.EXTRA_LABEL, label);
                // v0.91.9：全屏页点「稍后提醒」也要按起始日记账，跟着一起带过去
                if (origin != null) full.putExtra(EXTRA_SNOOZE_ORIGIN, origin);
                full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(full);
            } catch (Exception ignore) {
            }
        }
        // 已经响铃了，提前提醒的「即将响铃」通知就没用了——顺手撤掉，
        // 否则它会在通知栏一直挂到用户手动清理（v0.31.0 修）
        try {
            NotificationManagerCompat.from(context)
                    .cancel(PreNoticeReceiver.NOTIF_ID_BASE + alarmId);
        } catch (Exception ignore) {
        }
        // 通道二：前台服务播放铃声/震动 + 全屏 intent 通知
        Intent svc = new Intent(context, AlarmService.class);
        svc.putExtra(AlarmService.EXTRA_POPUP, popup);
        svc.putExtra(AlarmService.EXTRA_DATE, dateStr);
        svc.putExtra(AlarmService.EXTRA_ALARM_ID, alarmId);
        svc.putExtra(AlarmService.EXTRA_LABEL, label);
        if (origin != null) svc.putExtra(EXTRA_SNOOZE_ORIGIN, origin);
        try {
            ContextCompat.startForegroundService(context, svc);
        } catch (Exception startFailure) {
            // Android 12+ 在后台启动前台服务会被拒（精确闹钟降级成 setWindow 时最常见）。
            // 不接住 = 广播崩溃 + 闹钟不响；兜底成一条带全屏 intent 的通知。
            AlarmService.postFallbackNotification(context, alarmId, label, dateStr, popup);
        }
        // 触发后按当前排班重排，保证窗口始终有闹钟。
        // v0.23.0：重排要取消并重新注册上百个系统闹钟（每次都是跨进程 IPC，累计可达数百毫秒
        // 到数秒），放在广播主线程会拖慢响铃、极端情况下还会广播超时。改用 goAsync 挪到
        // 后台线程，主线程把界面和服务拉起后立即返回。
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                // v1.23.31：响铃后这次重排是全 App 最频繁的一次，失败同样要记账。
                RescheduleGuard.runTracked(app);
            } finally {
                pending.finish();
            }
        }, "alarm-reschedule").start();
    }
}
