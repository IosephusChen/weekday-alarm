package io.github.iosephuschen.weekdayalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

/**
 * 响铃自动停止（v0.16.0）。
 *
 * 由 {@link ScheduleManager#setAutoStop} 在闹钟开始响铃时注册一条定时广播，
 * 到点后停掉 AlarmService（铃声 / 震动 / 通知一并结束）。
 * 走广播而不是 PendingIntent.getService：Android 12+ 禁止从后台启动前台服务，
 * 而 stopService 没有这个限制，更稳。
 */
public class StopReceiver extends BroadcastReceiver {

    public static final String ACTION_AUTO_STOP = "io.github.iosephuschen.weekdayalarm.AUTO_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_AUTO_STOP.equals(intent != null ? intent.getAction() : null)) return;
        try {
            context.stopService(new Intent(context, AlarmService.class));
        } catch (Exception ignore) {
        }
        try {
            NotificationManagerCompat.from(context).cancel(AlarmService.NOTIF_ID);
        } catch (Exception ignore) {
        }
    }
}
