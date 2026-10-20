package com.daxz.weekalarm;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 前台服务：播放闹钟铃声 + 震动 + 发送可全屏的闹钟通知。 */
public class AlarmService extends Service {

    public static final String EXTRA_DATE = "date";
    /** 是否用全屏 intent 抢屏（v1.23.1）：由 AlarmReceiver 按锁屏状态决定，解锁时只发通知。 */
    public static final String EXTRA_POPUP = "popup";
    public static final String EXTRA_ALARM_ID = AlarmReceiver.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = AlarmReceiver.EXTRA_LABEL;
    public static final String ACTION_STOP = "com.daxz.weekalarm.STOP";
    public static final String ACTION_SNOOZE = "com.daxz.weekalarm.SNOOZE";
    public static final String ACTION_TEST = "com.daxz.weekalarm.TEST";

    /** 通知 id：StopReceiver 取消通知时要用到，故对外可见。 */
    public static final int NOTIF_ID = 1001;

    /** 铃声渐响：从静音线性升至设定音量的总时长与步进（v1.16）。 */
    private static final long FADE_IN_MS = 30_000;
    private static final long FADE_STEP_MS = 250;

    private MediaPlayer player;
    private Vibrator vibrator;
    private int currentAlarmId;   // 本次响铃的闹钟 id（小睡沿用）
    /** 本次响铃的日期（小睡计数 / 自动停止取消时用）。 */
    private LocalDate ringDate = LocalDate.now();
    /** 「自动停止」闹钟实际注册在哪一天（跨零点时与 ringDate 不同，取消必须用这个）。 */
    private LocalDate autoStopDate;

    // ===== 音量与渐响（v1.16）=====
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private long fadeStartUptime;
    private float fadeTargetVol = 1f;
    private boolean fading = false;
    private final Runnable fadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!fading || player == null) return;
            long elapsed = SystemClock.uptimeMillis() - fadeStartUptime;
            float t = Math.min(1f, elapsed / (float) FADE_IN_MS);
            applyPlayerVolume(fadeTargetVol * t);
            if (t < 1f) {
                fadeHandler.postDelayed(this, FADE_STEP_MS);
            } else {
                fading = false;
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 0);
            String label = intent.getStringExtra(EXTRA_LABEL);
            // v1.18.2 修复：通知栏的小睡按钮此前不看剩余次数，界面禁用后
            // 用户仍能从通知栏无限续睡。次数用尽时忽略操作并提示。
            LocalDate d = parseDate(intent.getStringExtra(EXTRA_DATE));
            if (!ScheduleManager.canSnooze(this, alarmId, d)) {
                Toast.makeText(this, "稍后提醒次数已用完，请停止响铃",
                        Toast.LENGTH_SHORT).show();
                return START_NOT_STICKY;
            }
            Prefs.incrementSnoozeCount(this, alarmId, d);
            ScheduleManager.snooze(this, alarmId, label != null ? label : "闹钟");
            stopSelf();
            return START_NOT_STICKY;
        }

        // v1.19.9：多个闹钟同时到点（如起床 7:30 与另一个 7:30）会连续进来两次 onStartCommand。
        // 旧版直接 new MediaPlayer 覆盖字段，前一个实例既没 stop 也没 release，
        // 结果两路铃声叠着响、且再也停不掉。这里先收尾上一次响铃。
        if (player != null || vibrator != null) {
            stopRinging();
        }
        String dateStr = intent != null ? intent.getStringExtra(EXTRA_DATE) : null;
        String label = intent != null ? intent.getStringExtra(EXTRA_LABEL) : null;
        if (label == null) label = "闹钟";
        currentAlarmId = intent != null ? intent.getIntExtra(EXTRA_ALARM_ID, 0) : 0;
        ringDate = parseDate(dateStr);
        boolean test = ACTION_TEST.equals(action);
        // 缺省 true：老路径（测试响铃直接起服务）没有带这个 extra，保持原来的全屏行为
        boolean popup = intent == null || intent.getBooleanExtra(EXTRA_POPUP, true);
        startForeground(NOTIF_ID, buildNotification(dateStr, label, test, popup),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        startRinging();
        scheduleAutoStop();   // v1.18：设定了响铃时长时，到点自动停
        return START_NOT_STICKY;
    }


    /** 注册「响铃自动停止」闹钟：到点触发 StopReceiver，避免无人时一直响。 */
    private void scheduleAutoStop() {
        int minutes = Prefs.getRingMinutes(this);
        if (minutes <= 0) return;
        LocalDateTime when = LocalDateTime.now().plusMinutes(minutes);
        // v1.19.9：记录实际注册日期。23:58 响铃 + 5 分钟自动停止会落到次日，
        // 旧版取消时用 ringDate（今天）算 requestCode，打不中，残留一条无用广播。
        autoStopDate = when.toLocalDate();
        ScheduleManager.setAutoStop(this, when, currentAlarmId);
    }

    /** 解析日期字符串；为空或非法时回退到今天。 */
    private static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty()) return LocalDate.now();
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private void startRinging() {
        // v1.16：音量可调（0 = 静音仅震动），可选铃声渐响
        int volumePct = Prefs.getVolume(this);
        float targetVol = Math.max(0f, Math.min(1f, volumePct / 100f));
        boolean fadeIn = Prefs.isFadeIn(this) && volumePct > 0;
        try {
            Uri uri = resolveRingtoneUri();
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(true);
            player.prepare();
            applyVolumeAndStart(targetVol, fadeIn);
        } catch (Exception e) {
            try {
                player = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
                if (player != null) {
                    player.setLooping(true);
                    applyVolumeAndStart(targetVol, fadeIn);
                }
            } catch (Exception ignore) {
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                VibratorManager vm = getSystemService(VibratorManager.class);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            // v1.18：振动可关——配合音量 0 可实现「静音仅震动」或完全安静
            if (Prefs.isVibrate(this) && vibrator != null && vibrator.hasVibrator()) {
                // 带 USAGE_ALARM 属性的强震动：勿扰/静音策略下也会震，且幅度拉满
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                long[] timings = {0L, 1000L, 800L};   // 第1段0ms（占位）+ 震1000ms + 停800ms
                int[] amplitudes = {0, 255, 0};        // 每段幅度：0=停、255=满幅
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0), attrs);
            }
        } catch (Exception e) {
            // 兜底：旧接口
            try {
                if (vibrator != null && vibrator.hasVibrator()) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM).build();
                    vibrator.vibrate(new long[]{0L, 1000L, 800L}, 0, attrs);
                }
            } catch (Exception ignore) {
            }
        }
    }

    /** 本次响铃使用的铃声：用户自定义优先，否则系统默认闹钟铃（v1.19.1）。 */
    Uri resolveRingtoneUri() {
        Uri custom = Prefs.getRingtoneUri(this);
        if (custom != null) return custom;
        Uri def = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        return def != null ? def : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    /** 按设定音量开始播放；开启渐响时从 0 线性爬升（v1.16）。 */
    private void applyVolumeAndStart(float targetVol, boolean fadeIn) {
        if (player == null) return;
        try {
            if (fadeIn) {
                player.setVolume(0f, 0f);
                fadeTargetVol = targetVol;
                fadeStartUptime = SystemClock.uptimeMillis();
                fading = true;
                fadeHandler.post(fadeRunnable);
            } else {
                player.setVolume(targetVol, targetVol);
            }
            player.start();
        } catch (Exception ignore) {
        }
    }

    private void applyPlayerVolume(float v) {
        if (player == null) return;
        try {
            player.setVolume(v, v);
        } catch (Exception ignore) {
        }
    }

    private void stopRinging() {
        fading = false;
        fadeHandler.removeCallbacks(fadeRunnable);
        // 取消尚未触发的「自动停止」闹钟，避免它在停止之后又来一次（v1.18）。
        // 用注册时记录的实际日期，跨零点也能打中（v1.19.9）
        LocalDate stopDate = autoStopDate != null ? autoStopDate
                : (ringDate != null ? ringDate : LocalDate.now());
        ScheduleManager.cancelAutoStop(this, currentAlarmId, stopDate);
        autoStopDate = null;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignore) {
            }
            player.release();
            player = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignore) {
            }
            vibrator = null;
        }
    }

    private Notification buildNotification(String dateStr, String label, boolean test, boolean popup) {
        Intent full = new Intent(this, AlarmActivity.class);
        full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
        full.putExtra(AlarmActivity.EXTRA_TEST, test);
        full.putExtra(AlarmActivity.EXTRA_ALARM_ID, currentAlarmId);
        full.putExtra(AlarmActivity.EXTRA_LABEL, label);
        // 只用 NEW_TASK：AlarmActivity 是 singleInstance，复用同一实例；
        // 若带 CLEAR_TASK，界面已弹出时会被销毁重建，造成闪烁
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent fpi = PendingIntent.getActivity(this, 0, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, AlarmService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        // v1.19.9：补上 EXTRA_DATE。之前漏传，点通知栏小睡时日期回退成「今天」，
        // 跨零点（23:58 响、00:02 点小睡）会把次数记到新的一天，原日期的计数不生效
        PendingIntent snoozePi = PendingIntent.getService(this, 2,
                new Intent(this, AlarmService.class).setAction(ACTION_SNOOZE)
                        .putExtra(EXTRA_ALARM_ID, currentAlarmId)
                        .putExtra(EXTRA_LABEL, label)
                        .putExtra(EXTRA_DATE, ringDate != null ? ringDate.toString() : null),
                PendingIntent.FLAG_IMMUTABLE);

        String title = test ? "测试：闹钟响铃" : label + "时间到了";
        String text = (dateStr != null ? dateStr + " " : "")
                + (test ? "这是测试，正式提醒一样响" : "点此停止或小睡");
        // 测试响铃用低优先级渠道：界面已经由「测试响铃」按钮打开了，再来一条横幅就是重复
        Notification.Builder nb = new Notification.Builder(this,
                test ? App.CHANNEL_TEST : App.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(fpi)   // 全屏 intent 被系统拒绝时，点通知同样能打开闹钟页
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                // v1.23.3：action 必须带图标，否则 MIUI / HyperOS 不渲染按钮
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notif_close),
                        test ? "结束测试" : "停止", stopPi).build());
        // 只有息屏 / 锁屏才挂全屏 intent：解锁使用中让系统只弹横幅，
        // 避免「通知 + 整屏弹窗」同时出现（v1.23.1）
        if (popup && !test) {
            nb.setFullScreenIntent(fpi, true);
        }
        // 小睡按钮：次数用尽时不再提供，避免通知栏无限续睡（v1.18.2）。
        // v1.23：测试模式也不给——测试闹钟没有排班实例，点了只会静默地「什么都不发生」，
        // 用户会以为按钮坏了；现在测试通知里只留「结束测试」。
        if (!test && ScheduleManager.canSnooze(this, currentAlarmId, ringDate)) {
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif_snooze),
                    Math.max(1, Prefs.getSnoozeMinutes(this)) + "分钟后再响", snoozePi).build());
        }
        return nb.build();
    }

    @Override
    public void onDestroy() {
        stopRinging();
        NotificationManagerCompat.from(this).cancel(NOTIF_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
