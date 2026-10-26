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
        // 锁屏 / 息屏：直接拉整屏（那时候通知和小窗都没人看得见）。
        // 解锁使用中：不在这里拉界面，交给 AlarmService 弹一个 300dp 的小悬浮窗——
        // v0.53.2 试过「解锁也整屏」，用户反馈太粗暴：正在用手机时被整屏盖住，
        // 要的是「小窗提醒一下」。挂不上悬浮窗时服务自己退回整屏。
        final boolean popup = ScreenLock.shouldPopup(context);
        if (popup) {
            try {
                Intent full = new Intent(context, AlarmActivity.class);
                full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
                full.putExtra(AlarmActivity.EXTRA_TEST, false);
                full.putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId);
                full.putExtra(AlarmActivity.EXTRA_LABEL, label);
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
                ScheduleManager.rescheduleAll(app);
                // 响铃后下一个闹钟已变，同步刷新桌面小组件（v0.18.0；v0.24.0 起含本周班表）
                Widgets.updateAll(app);
            } catch (Throwable ignore) {
                // 重排失败不影响本次响铃；下次响铃 / 重启会再补
            } finally {
                pending.finish();
            }
        }, "alarm-reschedule").start();
    }
}
