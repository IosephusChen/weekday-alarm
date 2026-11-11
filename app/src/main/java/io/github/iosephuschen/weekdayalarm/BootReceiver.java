package io.github.iosephuschen.weekdayalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后，按当前排班规则重设全部闹钟。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        // v1.23.31：60 秒前那次重排失败后的重试。自己不再续挂——重试再失败就只留标记，
        // 等下次开机或用户打开 App 再补（续挂会变成每 60 秒一次的后台闹钟）。
        if (RescheduleGuard.ACTION_RETRY.equals(a)) {
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    RescheduleGuard.run(app, RescheduleGuard.FULL, false);
                } finally {
                    pending.finish();
                }
            }, "reschedule-retry").start();
            return;
        }
        // v0.23.0：除开机 / 升级外，还要处理「时区变了」与「系统时间被改」。
        // 闹钟是按 LocalDateTime 换算成 epoch 毫秒注册的，时区一换（出国、手动改时区）
        // 或用户手动调时间，已注册实例的绝对时刻就偏了——旧版不重排，闹钟会在错误的
        // 钟点响。这两个广播在 Android 8+ 的隐式广播豁免名单内，可静态注册。
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)) {
            // v0.23.0：开机广播同样有 10 秒时限，重排上百个闹钟可能超时导致 ANR。
            // goAsync 后放到后台线程执行，主线程立即返回。
            // v1.23.31：改走 RescheduleGuard——失败要留标记并挂一次重试，原来那个
            // catch (Throwable ignore) 会让「开机后一个闹钟都没排上」无人知晓。
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    // 一处包住三件事：闹钟、睡前提醒（v0.24.0 重启 / 改时区要重挂）、
                    // 桌面组件（v0.18.0；v0.24.0 起含本周班表）
                    RescheduleGuard.runTracked(app);
                } finally {
                    pending.finish();
                }
            }, "boot-reschedule").start();
        }
    }
}
