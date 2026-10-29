package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 前台服务：播放闹钟铃声 + 震动 + 发送可全屏的闹钟通知。 */
public class AlarmService extends Service {

    public static final String EXTRA_DATE = "date";
    /** 是否用全屏 intent 抢屏（v0.31.1）：由 AlarmReceiver 按锁屏状态决定，解锁时只发通知。 */
    public static final String EXTRA_POPUP = "popup";
    public static final String EXTRA_ALARM_ID = AlarmReceiver.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = AlarmReceiver.EXTRA_LABEL;
    public static final String ACTION_STOP = "io.github.iosephuschen.weekdayalarm.STOP";
    public static final String ACTION_SNOOZE = "io.github.iosephuschen.weekdayalarm.SNOOZE";
    public static final String ACTION_TEST = "io.github.iosephuschen.weekdayalarm.TEST";

    /** 通知 id：StopReceiver 取消通知时要用到，故对外可见。 */
    public static final int NOTIF_ID = 1001;

    /**
     * 当前是否正在响铃（v0.58.0）。
     *
     * <p>存在的理由：响铃时能停止的入口只有两个——整屏响铃页和通知栏的「停止」按钮，
     * 而这两个都可能被系统拦掉（后台拉起 Activity 受限 / 通知权限被关）。用户回到 App
     * 却发现无处可按，只能强行停止。这个标志让主界面能无条件显示一个「停止」入口。
     *
     * <p>静态字段够用：响铃服务与主界面同进程；进程被杀时铃声也随之消失。
     */
    private static volatile boolean ringing;

    public static boolean isRinging() {
        return ringing;
    }

    /** 通知不可用时，响铃的最长时长（分钟）：没有停止入口，必须自己停（v0.58.0）。 */
    static final int DEGRADED_MAX_MINUTES = 5;

    /** 铃声渐响的总时长（v0.14.0）。 */
    private static final long FADE_IN_MS = 30_000;
    /** 渐响步进：v0.91.2 从 250ms 提到 50ms，与系统时钟一致，爬升听不出台阶。 */
    private static final long FADE_STEP_MS = 50;
    /** 渐响的分贝跨度：-40dB 起步，0dB 到顶（v0.91.2，照 AOSP DeskClock）。 */
    private static final float FADE_RANGE_DB = 40f;

    private MediaPlayer player;
    private Vibrator vibrator;
    private int currentAlarmId;   // 本次响铃的闹钟 id（小睡沿用）
    /** 本次是不是「试听 / 测试响铃」（v0.91.1 起影响渐响与诊断行）。 */
    private boolean testRing;
    /** 本次实际播的是哪条铃声的标题，只给测试模式的诊断行用。 */
    private String playingRingtoneName;

    /** 音频焦点（v0.91.1）：没有它，部分 ROM 会把 USAGE_ALARM 的播放压掉。 */
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    /**
     * 最近一次响铃的播放诊断（v0.91.1）。
     *
     * <p>为什么要有这个：v0.90.x 之前，铃声放不出来时两处 {@code catch} 都是
     * {@code ignore}——界面上完全看不出发生过什么，用户只能反复说「没声音」，而代码里
     * 一片安静。这里把「用什么铃声、系统音量多少、播放是否成功」摊开，只给试听模式看，
     * 正式响铃页一个字都不显示。静态字段够用：服务与响铃页同进程。
     */
    private static volatile String lastRingDiagnostic;
    /** 本次响铃的日期（小睡计数 / 自动停止取消时用）。 */
    private LocalDate ringDate = LocalDate.now();
    /** 「自动停止」闹钟实际注册在哪一天（跨零点时与 ringDate 不同，取消必须用这个）。 */
    private LocalDate autoStopDate;

    // ===== 音量与渐响（v0.14.0）=====
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private long fadeStartUptime;
    private float fadeTargetVol = 1f;
    private boolean fading = false;
    /** 渐响进度百分比（v0.91.2）：-1 表示没在渐响，只给试听模式的诊断行读。 */
    private int fadePercent = -1;
    private final Runnable fadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!fading || player == null) return;
            long elapsed = SystemClock.uptimeMillis() - fadeStartUptime;
            float t = Math.min(1f, elapsed / (float) FADE_IN_MS);
            applyPlayerVolume(fadeTargetVol * fadeGain(t));
            // 诊断行每变化一个百分点才重拼一次，不在 50ms 循环里拼 20 次字符串
            int pct = Math.round(t * 100f);
            if (pct != fadePercent) {
                fadePercent = pct;
                if (testRing) updateDiagnostic();
            }
            if (t < 1f) {
                fadeHandler.postDelayed(this, FADE_STEP_MS);
            } else {
                fading = false;
                fadePercent = -1;
                if (testRing) updateDiagnostic();
            }
        }
    };

    /**
     * 渐响曲线：给定时间进度 {@code t}（0→1），返回振幅系数。
     *
     * <p>v0.91.2 之前这里是振幅线性（{@code 直接用 t}）。那是错的：人耳对响度的感知
     * 接近对数，振幅线性听起来是「前半程一下子变响，后半程几乎没变化」。
     * AOSP DeskClock 的做法是按分贝线性——{@code -40dB} 匀速升到 {@code 0dB}——
     * 振幅便呈指数，听感才是「匀速变响」。
     *
     * <p>一个直观的对照：进度过半时，旧曲线给 50% 振幅，新曲线只有 10%。
     * 前半程安静得多，这既是「像慢慢醒来」的来源，也是试听头几秒几乎听不见的原因，
     * 所以试听页那行诊断要把进度数字亮出来，让人知道它在爬升而不是坏了。
     */
    static float fadeGain(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float gainDb = (t - 1f) * FADE_RANGE_DB;
        return (float) Math.pow(10, gainDb / 20.0);
    }

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
            // v0.17.1 修复：通知栏的小睡按钮此前不看剩余次数，界面禁用后
            // 用户仍能从通知栏无限续睡。次数用尽时忽略操作并提示。
            LocalDate d = parseDate(intent.getStringExtra(EXTRA_DATE));
            if (!ScheduleManager.canSnooze(this, alarmId, d)) {
                Toast.makeText(this, "稍后提醒次数已用完，请滑动停止",
                        Toast.LENGTH_SHORT).show();
                return START_NOT_STICKY;
            }
            Prefs.incrementSnoozeCount(this, alarmId, d);
            int mins = Math.max(1, Prefs.getSnoozeMinutes(this));
            ScheduleManager.snooze(this, alarmId, label != null ? label : "闹钟");
            // v0.53.1：小睡排好之后立刻补一条通知。原来这里只 stopSelf，通知栏随响铃通知
            // 一起清空，用户点完「5 分钟后再响」什么反馈都看不到，不知道它到底还响不响。
            SnoozeNotice.post(this, alarmId, label != null ? label : "闹钟", mins);
            // v0.55.2：横幅点「稍后提醒」后立刻消失，通知栏那条要等一两秒才出现，
            // 中间屏幕上什么都没有，用户会以为没点上而再点一次（于是多排一次小睡）。
            // 这里补一句屏幕上的回执。
            FloatingAlarm.flash(this,
                    (label != null && !label.isEmpty() ? label : "闹钟")
                            + " " + mins + " 分钟后再响");
            stopSelf();
            return START_NOT_STICKY;
        }

        // v0.23.0：多个闹钟同时到点（如起床 7:30 与另一个 7:30）会连续进来两次 onStartCommand。
        // 旧版直接 new MediaPlayer 覆盖字段，前一个实例既没 stop 也没 release，
        // 结果两路铃声叠着响、且再也停不掉。这里先收尾上一次响铃。
        if (player != null || vibrator != null) {
            stopRinging();
        }
        String dateStr = intent != null ? intent.getStringExtra(EXTRA_DATE) : null;
        String label = intent != null ? intent.getStringExtra(EXTRA_LABEL) : null;
        if (label == null) label = "闹钟";
        currentAlarmId = intent != null ? intent.getIntExtra(EXTRA_ALARM_ID, 0) : 0;
        // v0.53.1：真的响了，那条「N 分钟后再次响铃」的预告就该撤——留着会变成
        // 两条同时在栏里，用户分不清哪条是现在响的
        SnoozeNotice.cancel(this, currentAlarmId);
        // v0.58.8：响铃＝这一夜结束，昨晚那条「明天 07:30 起床」已经兑现，留着就是废话
        BedtimeReminder.cancelNotice(this);
        ringDate = parseDate(dateStr);
        boolean test = ACTION_TEST.equals(action);
        testRing = test;
        // 缺省 true：老路径（测试响铃直接起服务）没有带这个 extra，保持原来的全屏行为
        boolean popup = intent == null || intent.getBooleanExtra(EXTRA_POPUP, true);
        Notification notif = buildNotification(dateStr, label, test, popup);
        // startForeground(id, notification, type) 是 API 29 的；minSdk 26，老系统用两参数重载
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Exception startFailure) {
            // Android 12+ 后台启动前台服务可能被系统拒绝（精确闹钟降级到 setWindow 时）。
            // 崩在这里 = 闹钟到点闪退且不响，所以兜底成一条带全屏 intent 的通知。
            postFallbackNotification(this, currentAlarmId, label, dateStr, popup);
            // 必须 stopForeground 再 stopSelf：startForegroundService 起的服务若在
            // onStartCommand 里没成功置前台就退，系统会补一个
            // "did not then call Service.startForeground()" 的 RemoteServiceException
            try {
                stopForeground(true);
            } catch (Exception ignore) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        ringing = true;
        startRinging();
        scheduleAutoStop();   // v0.16.0：设定了响铃时长时，到点自动停
        // v0.54.0：解锁使用中不整屏，弹一个 300dp 的小悬浮窗；挂不上去（没给悬浮窗权限）
        // 才退回整屏。锁屏 / 息屏仍由 AlarmReceiver 直接拉整屏，这里不再重复。
        if (!popup && !test) {
            int mins = Math.max(1, Prefs.getSnoozeMinutes(this));
            boolean canSnooze = ScheduleManager.canSnooze(this, currentAlarmId, ringDate);
            // v0.55.0：横幅上要显示大号响铃时刻。取闹钟自己的 HH:mm 而不是当前时间——
            // 迟到几十秒（系统排程有抖动）时，显示「07:30」才是用户认识的那个闹钟
            if (!FloatingAlarm.show(this, currentAlarmId, label, dateStr,
                    ringTimeText(currentAlarmId), mins, canSnooze)) {
                openAlarmPage(dateStr, label);
            }
        }
        return START_NOT_STICKY;
    }

    /** 兜底：把整屏响铃页拉到前面（悬浮窗挂不上、或锁屏时用）。 */
    /**
     * 本次响铃的闹钟时刻（HH:mm）。
     *
     * <p>按 id 去列表里找；找不到（闹钟在响铃期间被删掉、或测试响铃没带 id）
     * 就返回 null，让 {@link FloatingAlarm} 退回当前时间——宁可显示「现在几点」，
     * 也不要显示一个空字符串。
     */
    private String ringTimeText(int alarmId) {
        try {
            for (Alarm a : Prefs.getAlarmsSettled(this)) {
                if (a.id == alarmId) return a.timeText();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void openAlarmPage(String dateStr, String label) {
        try {
            Intent full = new Intent(this, AlarmActivity.class);
            full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
            full.putExtra(AlarmActivity.EXTRA_ALARM_ID, currentAlarmId);
            full.putExtra(AlarmActivity.EXTRA_LABEL, label);
            full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(full);
        } catch (Exception ignore) {
        }
    }


    /** 注册「响铃自动停止」闹钟：到点触发 StopReceiver，避免无人时一直响。 */
    /**
     * 响铃时，App 之外是否还有能停的入口（v0.58.7）。
     *
     * <p>通知栏的「停止」按钮、悬浮窗横幅上的停止键，只要有一个在，主界面再挂一行
     * 「正在响铃 · 停止」就是重复——用户截图里三个停止按钮并列，看着像三件不同的事。
     *
     * <p>两个都没有时才返回 false：那时主界面那一行是唯一能停的地方（v0.58.0 的初衷）。
     */
    static boolean hasStopEntryOutside(Context ctx) {
        if (ctx == null) return false;
        boolean notif;
        try {
            notif = NotificationManagerCompat.from(ctx).areNotificationsEnabled();
        } catch (Throwable t) {
            notif = false;      // 查不到就当没有：宁可多给一个入口，也别让人停不下来
        }
        return notif || FloatingAlarm.isShowing();
    }

    /**
     * 本次响铃到底该响多久（分钟），0 = 不限。
     *
     * <p>v0.58.0：通知被关掉时，「停止」按钮就没了——整屏响铃页也可能被系统拦（后台拉起
     * Activity 受限），没给悬浮窗就一条路都没有，用户只能强行停止 App。这种状态不能让铃声
     * 无限响下去，所以强制封顶 {@link #DEGRADED_MAX_MINUTES}；用户设了更短的按用户的来。
     *
     * <p>包级可见是为了单测：这一条是「关不掉的闹钟」的唯一刹车。
     */
    static int effectiveRingMinutes(Context ctx) {
        int minutes = Prefs.getRingMinutes(ctx);
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            minutes = minutes <= 0 ? DEGRADED_MAX_MINUTES
                    : Math.min(minutes, DEGRADED_MAX_MINUTES);
        }
        return minutes;
    }

    private void scheduleAutoStop() {
        int minutes = effectiveRingMinutes(this);
        if (minutes <= 0) return;
        LocalDateTime when = LocalDateTime.now().plusMinutes(minutes);
        // v0.23.0：记录实际注册日期。23:58 响铃 + 5 分钟自动停止会落到次日，
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
        // v0.90.0：静音的闹钟根本不创建 player —— 不是把音量拖到 0，而是不进这条路。
        // 其余照旧：前台服务、全屏页、振动、通知、自动停止倒计时都归下面几段管。
        // 这样「静音」和「音量很小」是两件事：前者不占音频焦点，也不会被系统音量影响
        if (!isSilentAlarm(this, currentAlarmId)) {
            // v0.91.0：响度不再乘 App 的系数，完全交给系统 STREAM_ALARM。
            // setVolume(1.0) 的意思是「按系统音量放满」——设置页显示 80%，
            // 实际就是系统闹钟那一档的 80%，不再是 80% × 系统档位的两层相乘。
            // 渐响仍从 0 渐到 1.0，在系统音量之内做，听感不变。
            //
            // v0.91.2：试听也照设置渐响。上一版为了让「听不见」好定位，替试听关掉了渐响，
            // 等于让试听演示的效果和明天早上真实发生的不一样——试听的全部意义就是预演，
            // 关掉渐响就把它变成了谎话。真问题在曲线，见 fadeGain()。
            float targetVol = 1f;
            boolean fadeIn = shouldFadeIn(this);
            // v0.91.1：先拿焦点再播。没有焦点时部分 ROM 会把 USAGE_ALARM 压掉，
            // 表现同样是「界面正常、一点声音都没有」。
            requestFocusForAlarm();
            String lastError = null;
            for (Uri uri : ringtoneChain()) {
                lastError = startPlayer(uri, targetVol, fadeIn);
                if (lastError == null) break;
            }
            if (player == null) {
                // v0.91.1：整条链都失败时不再沉默。以前这里是两处空 catch，
                // 用户只能反复说「没声音」，代码里一点线索都不留。
                lastRingDiagnostic = "播放失败：" + (lastError != null ? lastError : "没有可用的铃声");
            } else {
                updateDiagnostic();
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                VibratorManager vm = getSystemService(VibratorManager.class);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            // v0.16.0：振动可关——配合音量 0 可实现「静音仅震动」或完全安静
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

    /**
     * 本次响铃使用的铃声（v0.88.0 起三级回落）：
     * 闹钟单独设的 → 设置里的默认 → 系统默认闹钟铃。
     *
     * <p>中间那级不能省：绝大多数闹钟没单独设过铃声（{@code ringtone == null}），
     * 它们必须跟着「设置 → 铃声」走，否则用户在设置里换铃声会觉得没生效。
     */
    Uri resolveRingtoneUri() {
        return resolveRingtoneUri(this, currentAlarmId);
    }

    /** 拆出来便于单测：给定闹钟 id，算出该用哪条铃声。 */
    static Uri resolveRingtoneUri(Context ctx, int alarmId) {
        Uri per = alarmRingtone(ctx, alarmId);
        if (per != null) return per;
        Uri custom = Prefs.getRingtoneUri(ctx);
        if (custom != null) return custom;
        Uri def = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM);
        return def != null ? def : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    /**
     * 这次要不要渐响（v0.91.1 抽出，便于单测）。
     *
     * <p>两个条件缺一不可：用户开了渐响、系统闹钟音量不是 0。
     * 第二条是 v0.90.0 留下的：音量在 0 时渐响等于「从 0 爬到 0」，白白延后 30 秒
     * 才让人意识到根本没声音，不如直接放，让他立刻知道是音量问题。
     *
     * <p>v0.91.1 曾把试听排除在外，理由是「点两下就关掉的人听不到声」。那是治标：
     * 试听的意义就是「先听一遍明天早上会被怎么叫醒」，替用户关掉渐响等于骗他。
     * v0.91.2 起试听照设置走，真正的问题——旧曲线前段涨得太快、后段几乎没变化——
     * 改由 {@link #fadeGain(float)} 的分贝曲线解决。
     */
    static boolean shouldFadeIn(Context c) {
        return Prefs.isFadeIn(c) && AlarmVolume.level(c) > 0;
    }

    /**
     * 本次响铃的铃声候选，按优先级去重（v0.91.1 起四级）。
     *
     * <p>前三级的顺序沿用 v0.88.0：闹钟单独设的 → 设置里的默认 → 系统默认闹钟铃。
     *
     * <p>第四级是 v0.91.1 新加的，专治一种没有任何异常可抓的哑巴故障：系统「闹钟」铃声
     * 被设成「无」时（部分 ROM 允许，此时 {@code content://settings/system/alarm_alert}
     * 指向一个静音文件），前三级都能正常 prepare、正常 start，就是一点声音都没有。
     * 用户感受到的与「播放失败」完全一样，而代码里风平浪静。通知铃声几乎不会被设成静音。
     *
     * <p>用 {@link LinkedHashSet} 去重同时保序：系统默认闹钟铃与第三级常常是同一个 Uri，
     * 重复试一遍纯属浪费一次 prepare。
     */
    List<Uri> ringtoneChain() {
        return ringtoneChain(this, currentAlarmId);
    }

    /** 拆出来便于单测：给定闹钟 id，排出这次要依次尝试的铃声。 */
    static List<Uri> ringtoneChain(Context ctx, int alarmId) {
        LinkedHashSet<Uri> set = new LinkedHashSet<>();
        Uri per = alarmRingtone(ctx, alarmId);
        if (per != null) set.add(per);
        Uri custom = Prefs.getRingtoneUri(ctx);
        if (custom != null) set.add(custom);
        Uri alarm = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM);
        if (alarm == null) alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarm != null) set.add(alarm);
        Uri notif = RingtoneManager.getActualDefaultRingtoneUri(ctx,
                RingtoneManager.TYPE_NOTIFICATION);
        if (notif != null) set.add(notif);
        return new ArrayList<>(set);
    }

    /**
     * 按给定 Uri 起一个播放器。
     *
     * @return {@code null} 表示已经开播；否则是一句可以显示给用户的原因（v0.91.1）
     */
    private String startPlayer(Uri uri, float targetVol, boolean fadeIn) {
        if (uri == null) return "铃声地址为空";
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(this, uri);
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            // v0.91.1：屏幕熄灭后 CPU 会休眠，MediaPlayer 跟着断，铃声自己停在一半。
            // 系统时钟（DeskClock）同样持 PARTIAL_WAKE_LOCK 放闹钟。
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setLooping(true);
            mp.prepare();
            player = mp;
            playingRingtoneName = testRing ? ringtoneLabel(uri) : null;
            applyVolumeAndStart(targetVol, fadeIn);
            return null;
        } catch (Exception e) {
            // 失败的实例必须就地回收：以前它被下一次尝试覆盖，MediaPlayer 一直不 release
            if (mp != null) {
                try {
                    mp.release();
                } catch (Exception ignore) {
                }
            }
            if (player == mp) player = null;
            String msg = e.getMessage();
            return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
        }
    }

    /** 铃声标题，取不到就退回 Uri 末段——诊断行里有个名字总比「未知」强。 */
    private String ringtoneLabel(Uri uri) {
        try {
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r != null) {
                String t = r.getTitle(this);
                if (t != null && !t.isEmpty()) return t;
            }
        } catch (Exception ignore) {
        }
        String seg = uri.getLastPathSegment();
        return seg == null ? "未知" : seg;
    }

    /** 拼一句给试听模式看的诊断；正式响铃一个字都不写（v0.91.1，v0.91.2 加渐响进度）。 */
    private void updateDiagnostic() {
        if (!testRing) return;
        StringBuilder sb = new StringBuilder();
        sb.append("系统音量 ").append(AlarmVolume.percent(this)).append('%');
        if (playingRingtoneName != null) sb.append(" · 铃声：").append(playingRingtoneName);
        if (player != null) {
            if (fading && fadePercent >= 0) {
                // 分贝曲线前段极轻，光写「播放中」会让人以为坏了。进度数字说明它在爬
                sb.append(" · 渐响中 ").append(fadePercent).append('%');
            } else {
                sb.append(" · 播放中");
            }
        }
        lastRingDiagnostic = sb.toString();
    }

    /** 响铃页试听模式下要显示的那行小字；{@code null} 表示没什么可说的（v0.91.1）。 */
    static String diagnostic() {
        return lastRingDiagnostic;
    }

    /**
     * 申请短暂的音频焦点（v0.91.1）。
     *
     * <p>{@code USAGE_ALARM} 管的是「这条声音算什么用途」，焦点管的是「谁在占用音频输出」，
     * 两回事。没有焦点时，系统仍可能按「输出正被别的应用占着」处理，把我们的播放压下去。
     * 用 {@code AUDIOFOCUS_GAIN_TRANSIENT} 而不是 GAIN：响铃结束就还回去，不长期霸占。
     * 系统时钟也是这么申请的。
     */
    private void requestFocusForAlarm() {
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focus -> {
                        // 闹钟不响应「被抢焦点」：它就该一直响到用户亲手停。
                        // 留一个空监听是因为 Builder 必填，不是为了在这里做决定。
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception ignore) {
            // 拿不到焦点也要继续放：很多机器根本不在意这件事
        }
    }

    /** 停止后把焦点还回去（v0.91.1）。 */
    private void abandonFocusForAlarm() {
        try {
            if (audioManager != null && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Exception ignore) {
        }
        focusRequest = null;
        audioManager = null;
    }

    /**
     * 某条闹钟是不是静音（v0.90.0）。
     *
     * <p>和 {@link #alarmRingtone} 一样按 id 现查，而不是在响铃开始时把值拷进字段：
     * 响铃期间用户可能进 App 把它改成静音或改回来，以最新状态为准。
     */
    static boolean isSilentAlarm(Context ctx, int alarmId) {
        if (alarmId <= 0) return false;
        for (Alarm a : Prefs.getAlarms(ctx)) {
            if (a.id == alarmId) return a.silent;
        }
        return false;
    }

    /**
     * 某条闹钟单独设的铃声；没设、或闹钟已经被删了，返回 null（交给下一级）。
     *
     * <p>「闹钟已被删」不是理论情况：响铃期间用户可以进 App 删除它，
     * 这时按 id 查不到，回落默认铃声比崩溃或静音都好。
     */
    static Uri alarmRingtone(Context ctx, int alarmId) {
        if (alarmId <= 0) return null;
        for (Alarm a : Prefs.getAlarms(ctx)) {
            if (a.id != alarmId) continue;
            if (a.ringtone == null || a.ringtone.isEmpty()) return null;
            try {
                return Uri.parse(a.ringtone);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 按设定音量开始播放；开启渐响时按分贝曲线从极轻爬到设定音量（v0.14.0 / v0.91.2）。 */
    private void applyVolumeAndStart(float targetVol, boolean fadeIn) {
        if (player == null) return;
        try {
            if (fadeIn) {
                player.setVolume(0f, 0f);
                fadeTargetVol = targetVol;
                fadeStartUptime = SystemClock.uptimeMillis();
                fading = true;
                fadePercent = 0;
                fadeHandler.post(fadeRunnable);
            } else {
                player.setVolume(targetVol, targetVol);
                fading = false;
                fadePercent = -1;
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
        // v0.54.0：小悬浮窗是独立窗口，不随通知一起消失，必须自己撤
        FloatingAlarm.hide();
        fading = false;
        fadePercent = -1;
        fadeHandler.removeCallbacks(fadeRunnable);
        // v0.91.1：焦点跟着这次响铃走，停了就还回去——否则下一次响铃申请时
        // 旧请求还挂着，部分 ROM 会当成「同一个应用重复申请」直接忽略
        abandonFocusForAlarm();
        playingRingtoneName = null;
        // 取消尚未触发的「自动停止」闹钟，避免它在停止之后又来一次（v0.16.0）。
        // 用注册时记录的实际日期，跨零点也能打中（v0.23.0）
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
        // 首页那行「正在响铃 · 停止」跟着 isRinging() 走，而它只在 refreshAll 时重算。
        // 用户开着 App、从通知栏点了「停止 / 稍后」，不广播的话那行会一直挂着（v0.89.10）。
        UiRefresh.request(this);
    }

    /**
     * 通知栏「稍后提醒」按钮的 PendingIntent（v0.73.3 抽出，供守门测试直接拿）。
     *
     * <p>必须带 {@code FLAG_UPDATE_CURRENT}：requestCode 固定为 2，PendingIntent 匹配
     * 不看 extras，少了这个 flag 第一次创建后 extras 就冻住了——之后任何闹钟响铃，
     * 点小睡小睡的都是第一个闹钟。回归测试见 {@code BugfixRegressionTest}。
     */
    static PendingIntent snoozePi(Context ctx, int alarmId, String label, String dateStr) {
        return PendingIntent.getService(ctx, 2,
                new Intent(ctx, AlarmService.class).setAction(ACTION_SNOOZE)
                        .putExtra(EXTRA_ALARM_ID, alarmId)
                        .putExtra(EXTRA_LABEL, label)
                        .putExtra(EXTRA_DATE, dateStr),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(String dateStr, String label, boolean test, boolean popup) {        Intent full = new Intent(this, AlarmActivity.class);
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
        // v0.23.0：补上 EXTRA_DATE。之前漏传，点通知栏小睡时日期回退成「今天」，
        // 跨零点（23:58 响、00:02 点小睡）会把次数记到新的一天，原日期的计数不生效
        // v0.73.3：补 FLAG_UPDATE_CURRENT。PendingIntent 的匹配只看 requestCode +
        // 组件，**不看 extras**——requestCode 固定为 2，第一个闹钟响铃时创建的这个
        // PendingIntent 会把 extras 永久冻住，之后任何闹钟再响，点通知栏小睡小睡的
        // 都是第一次那个闹钟（计数也记到旧 id / 旧日期）。同函数的 fpi 一直带着
        // UPDATE_CURRENT，这里属漏写。有了它，每次 post 通知都会把 extras 刷成当前闹钟。
        // 抽成静态方法供守门测试直接拿（BugfixRegressionTest）。
        PendingIntent snoozePi = snoozePi(this, currentAlarmId, label,
                ringDate != null ? ringDate.toString() : null);

        String title = test ? "测试：闹钟响铃" : label + "时间到了";
        // v0.54.0：卡片回到单行，正文只剩图标与药丸之间那一小条，写长了一句就被省略号吃掉
        String text = test ? "这是测试，正式提醒一样响" : "点此停止，或稍后提醒";
        // v0.54.2：渠道按「要不要自己弹界面」来选，这决定了系统会不会再叠一张 heads-up。
        //
        // 用户报的「两个悬浮窗」是这么来的：CHANNEL_ALARM 是 IMPORTANCE_HIGH，屏幕亮着时
        // 一 post 系统就弹 heads-up；我们又挂了自己的横幅，两张卡就叠在同一块地方。
        // 现在能弹横幅时走 IMPORTANCE_LOW 的 CHANNEL_ALARM_BANNER——界面已经由横幅负责，
        // 这条通知只需在栏里可点可停；锁屏 / 没给悬浮窗权限时才用高优先级渠道 + 全屏意图。
        boolean willFloat = !popup && !test && FloatingAlarm.canFloat(this);
        String channel = test ? App.CHANNEL_TEST
                : (willFloat ? App.CHANNEL_ALARM_BANNER : App.CHANNEL_ALARM);
        Notification.Builder nb = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(fpi)   // 全屏 intent 被系统拒绝时，点通知同样能打开闹钟页
                // v0.89.6：和另外三类通知保持一致。这条以前没设过，是四条里唯一
                // 走系统模板、不染色的一条（0.89.1 靠自绘底「顺带」统一了，现在
                // 自绘底撤了，要显式补上，否则响铃时通知栏里一张白卡夹在三张紫卡中间）
                .setColor(getColor(R.color.indigo))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false);
        // 全屏 intent 只在「确实要整屏」时挂：锁屏 / 息屏（popup），
        // 或者解锁但挂不了悬浮窗（没给权限）——那时候整屏是唯一的兜底。
        if (!test && (popup || !willFloat)) {
            nb.setFullScreenIntent(fpi, true);
        }
        // 小睡按钮：次数用尽时不再提供，避免通知栏无限续睡（v0.17.1）。
        // v0.31.0：测试模式也不给——测试闹钟没有排班实例，点了只会静默地「什么都不发生」，
        // 用户会以为按钮坏了；现在测试通知里只留「结束测试」。
        boolean canSnooze = !test && ScheduleManager.canSnooze(this, currentAlarmId, ringDate);
        int snoozeMins = Math.max(1, Prefs.getSnoozeMinutes(this));
        // v0.54.2：能弹横幅时不再挂 addAction。自绘卡片上已经有两个药丸，
        // 系统模板的 action 只在 heads-up / 展开态出现，重复且样式不一致。
        if (!willFloat) {
            // v0.31.3：action 必须带图标，否则 MIUI / HyperOS 不渲染按钮
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif_close),
                    test ? "结束测试" : "停止", stopPi).build());
            if (canSnooze) {
                nb.addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notif_snooze),
                        snoozeMins + "分钟后再响", snoozePi).build());
            }
        }
        // v0.53.1：折叠态换成自绘卡片。
        //
        // 用户看到的是「按钮是透明的」——那不是我们的样式，是系统模板给 addAction 的默认
        // 灰色半透明药丸。和提前提醒那条（v0.33.0 起自绘）放在一起，一眼就不像同一款 App。
        // 现在两条用同一套卡片：紫渐变图标、「停止」实色紫药丸、「N 分钟后再响」紫描边药丸。
        nb.setCustomContentView(NotifCard.build(this, title, text, 0, fpi,
                NotifCard.Button.primary(test ? "结束测试" : "停止", stopPi),
                canSnooze ? NotifCard.Button.secondary(snoozeMins + " 分钟后再响", snoozePi) : null));
        return nb.build();
    }

    /**
     * 前台服务起不来时的兜底通知（v0.74.2）。
     *
     * <p>精确闹钟权限被关掉后，{@code ScheduleManager} 会降级成 {@code setWindow}。
     * 那条闹钟触发时 App 不在「闹钟白名单」里，Android 12+ 的
     * {@code startForegroundService} / {@code startForeground} 会抛异常。
     * 不接住就是「闹钟到点闪退且不响」——所以这里至少留一条能点开、能停的通知。
     * 响铃本身起不来，但用户知道到点了，也不会以为手机坏了。
     */
    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS 可能被用户拒绝；下面 try-catch 兜底
    public static void postFallbackNotification(Context ctx, int alarmId, String label,
                                                String dateStr, boolean popup) {
        try {
            String name = label == null || label.isEmpty() ? "闹钟" : label;
            Intent full = new Intent(ctx, AlarmActivity.class);
            full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
            full.putExtra(AlarmActivity.EXTRA_TEST, false);
            full.putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId);
            full.putExtra(AlarmActivity.EXTRA_LABEL, name);
            full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // requestCode 带 alarmId：PendingIntent 匹配不看 extras，不带 id 会冻结成第一个闹钟
            PendingIntent fpi = PendingIntent.getActivity(ctx, 900 + alarmId, full,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stopPi = PendingIntent.getService(ctx, 1900 + alarmId,
                    new Intent(ctx, AlarmService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder nb = new Notification.Builder(ctx, App.CHANNEL_ALARM)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(name + "时间到了")
                    .setContentText("点此停止")
                    .setContentIntent(fpi)
                    .setColor(ctx.getColor(R.color.indigo))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setFullScreenIntent(fpi, true)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_notif_close),
                            "停止", stopPi).build());
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, nb.build());
        } catch (Exception ignore) {
            // 通知权限也没给：这一步再没什么可做的，交给下一条 / 用户自己发现
        }
    }

    @Override
    public void onDestroy() {
        ringing = false;
        stopRinging();
        NotificationManagerCompat.from(this).cancel(NOTIF_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
