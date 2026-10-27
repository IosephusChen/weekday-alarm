package io.github.iosephuschen.weekdayalarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 闹钟调度：用 AlarmManager.setAlarmClock 为每个启用的闹钟（{@link Alarm}）在未来
 * N 天（Prefs.getHorizonDays，默认 90）内按其重复规则（仅上班日/每天/仅休息日）排程。
 *
 * setAlarmClock 是系统级「闹钟」API：不需要精确闹钟权限、允许在打盹模式下触发、
 * 会在状态栏显示闹钟图标，是闹钟类 App 的正确做法。
 *
 * 多闹钟唯一性：PendingIntent requestCode = alarmId * 100000 + 日偏移（0~364），
 * 同一闹钟同一天只有一个；已登记记录以 "alarmId:yyyy-MM-dd" 形式持久化。
 *
 * ROM 闹钟数量硬限：部分厂商 ROM（三星已确认，MIUI/HyperOS 实测同样存在）对单个
 * 应用的已注册闹钟数有约 500 的上限，超出后 setAlarmClock 直接抛 SecurityException
 * 导致应用闪退。多闹钟 + 长 horizon（如「每天」响 × 365 天 + 上班日闹钟）很容易越过
 * 该上限，因此这里限制单次最多注册 MAX_REGISTERED_ALARMS 个，优先保证最近的实例；
 * 更远的实例不丢弃语义——每次闹钟响铃（AlarmReceiver）与重启（BootReceiver）都会
 * rescheduleAll 重新滚动补排，窗口随响铃不断前移，最终全部能排上。
 */
public final class ScheduleManager {

    public static final int HORIZON_DAYS = 90;
    public static final long SNOOZE_MINUTES = 5;

    /**
     * 单次注册的闹钟实例上限。留出到 500 硬限的余量（系统闹钟图标/其他组件也可能
     * 占用名额），超出的实例按「时间从近到远」截断，靠响铃后滚动补排。
     */
    public static final int MAX_REGISTERED_ALARMS = 400;

    private static final int REQUEST_BASE = 100000;   // 每个闹钟占 10 万个 requestCode 位

    /** 主闹钟广播的 action。单独提出来，因为升级清理时要按同一个字符串重建 PendingIntent。 */
    static final String ACTION_ALARM = "io.github.iosephuschen.weekdayalarm.ALARM";

    /**
     * v0.87.2 之前的 URI scheme，**只用于升级首启时清理残留**，新代码一律用
     * {@code weekdayalarm://}。
     *
     * <p>为什么不能直接删掉旧 scheme 就算完：AlarmManager 靠 PendingIntent 的
     * component + action + data 三项匹配来取消，scheme 一变，{@code am.cancel()}
     * 就打不中旧实例；而它们是一次性闹钟，会一直躺在系统里到各自到期——期间每天多触发
     * 一次（服务幂等，不会双铃，但会「响了一下又从头开始」），还挤占 ROM 的 500 个名额。
     *
     * <p>这也是 v0.23.0 踩过的同一个坑（requestCode 编码变了没清干净），
     * 所以这里照抄当时的解法：{@link #cancelLegacy}。
     */
    private static final String LEGACY_SCHEME = "daxz://";

    /**
     * requestCode 分段（相对 REQUEST_BASE）：主闹钟 0~364，提前通知 +50000，
     * 小睡 +70000。三段互不重叠（最大 70364 < 100000），因此：
     * - 取消某一个实例时不会误伤另一个（尤其小睡不会被 rescheduleAll 连带取消）；
     * - 每个「闹钟 id × 日期」最多占用 3 个系统闹钟名额，见下方截断策略。
     */
    private static final int SEG_MAIN = 0;
    private static final int SEG_PRE_NOTICE = 50000;
    private static final int SEG_SNOOZE = 70000;
    private static final int SEG_AUTOSTOP = 90000;

    private ScheduleManager() {
    }

    /**
     * 旧版日期偏移（相对「今天」的天数）。
     * 仅保留用于升级后清理历史残留闹钟——它本身是 v0.23.0 修复的那个 bug 的根源：
     * 偏移随「今天」滚动，跨天后再用同一算法算出的 requestCode 与注册时不一致，
     * 于是 cancel 打不中，旧实例一直留在系统里（会重复响、并挤占 ROM 闹钟名额）。
     */
    private static int legacyDayOffset(LocalDate d, LocalDate today) {
        long off = ChronoUnit.DAYS.between(today, d);
        if (off < 0) off = 0;
        if (off > 99999) off = 99999;
        return (int) off;
    }

    /**
     * 日期槽位：由**绝对日期**推导，与「今天是哪天」无关，因此同一实例在任何一天
     * 算出的 requestCode 都相同，cancel 一定打得中。
     *
     * 9000 天 ≈ 24.6 年一个循环，远超提醒范围（最长 365 天），碰撞无实际影响；
     * 取 9000 而非 100000 是为了给下面四个段偏移留出互不重叠的空间。
     */
    static int dateSlot(LocalDate d) {
        return (int) Math.floorMod(d.toEpochDay(), 9000L);
    }

    /**
     * 当天是否「实际要上班」：正常上班 / 调休补班，或个人标记加班的休息日（v0.13.0）。
     * 请假日返回 false（请假日不办公，见下方各处 isVacation 跳过）。
     */
    public static boolean isEffectiveWorkday(Context ctx, LocalDate d) {
        return WeekSchedule.isWorkday(d, Prefs.getRule(ctx))
                || Prefs.isOvertime(ctx, d);
    }

    /** requestCode = alarmId * 100000 + 段偏移 + 日偏移（相对今天）。 */
    private static int requestCode(int alarmId, LocalDate d) {
        return requestCode(alarmId, d, SEG_MAIN);
    }

    private static int requestCode(int alarmId, LocalDate d, int segment) {
        return alarmId * REQUEST_BASE + segment + dateSlot(d);
    }

    private static PendingIntent alarmPendingIntent(Context ctx, int alarmId, LocalDate d, String label) {
        return alarmPendingIntent(ctx, alarmId, d, label, SEG_MAIN);
    }

    private static PendingIntent alarmPendingIntent(Context ctx, int alarmId, LocalDate d,
                                                     String label, int segment) {
        Intent i = pendingIntentShell(ctx, alarmId, d, segment, label);
        return PendingIntent.getBroadcast(ctx, requestCode(alarmId, d, segment), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 取消一个闹钟在某天的实例（主闹钟段）。 */
    public static void cancelAlarm(Context ctx, int alarmId, LocalDate d) {
        cancelSegment(ctx, alarmId, d, SEG_MAIN);
    }

    /** 取消「提前通知」闹钟（v0.15.0）。 */
    public static void cancelPreNotice(Context ctx, int alarmId, LocalDate d) {
        cancelSegment(ctx, alarmId, d, SEG_PRE_NOTICE);
    }

    /**
     * 提前通知的 requestCode。注册（setPreNotice）与取消（cancelPreNotice）必须用同一套
     * 编码，因此统一由这里产出，PreNoticeReceiver 只是转发——v0.23.0 之前它自己抄了一份
     * 「相对今天偏移」的实现，两边一旦改动就会静默错位（取消不掉，通知照发）。
     */
    public static int preNoticeRequestCode(int alarmId, LocalDate d) {
        return requestCode(alarmId, d, SEG_PRE_NOTICE);
    }

    /** 取消待触发的「稍后提醒」闹钟（v0.15.0）。 */
    public static void cancelSnooze(Context ctx, int alarmId, LocalDate d) {
        cancelSegment(ctx, alarmId, d, SEG_SNOOZE);
    }

    /**
     * 是否还能再「稍后提醒」：次数不限（{@link Prefs#SNOOZE_UNLIMITED}）或当前已用次数 <
     * 上限时返回 true。通知栏小睡按钮与 AlarmService 共用此判断，避免两处逻辑漂移（v0.17.1）。
     */
    public static boolean canSnooze(Context ctx, int alarmId, LocalDate d) {
        int times = Prefs.getSnoozeTimes(ctx);
        if (times == Prefs.SNOOZE_UNLIMITED) return true;
        return Prefs.getSnoozeCount(ctx, alarmId, d) < times;
    }

    /** 检查「稍后提醒」闹钟是否仍在系统中登记（v0.15.0）。 */
    public static boolean isSnoozeRegistered(Context ctx, int alarmId, LocalDate d) {
        return isSegmentRegistered(ctx, alarmId, d, SEG_SNOOZE);
    }

    /** 检查「响铃自动停止」闹钟是否仍在系统中登记（v0.16.0）。 */
    public static boolean isAutoStopRegistered(Context ctx, int alarmId, LocalDate d) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        // 必须用 autoStopShell（StopReceiver + weekdayalarm://autostop/...），与 setAutoStop 完全一致；
        // 不能直接套 isSegmentRegistered 的 pendingIntentShell（指向 AlarmReceiver + weekdayalarm://alarm/...），
        // 否则 data URI / 目标 Receiver 不匹配，FLAG_NO_CREATE 探不到已注册的闹钟
        PendingIntent pi = PendingIntent.getBroadcast(ctx,
                requestCode(alarmId, d, SEG_AUTOSTOP),
                autoStopShell(ctx, alarmId, d),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        return pi != null;
    }

    /**
     * 注册一条「响铃自动停止」闹钟（v0.16.0）：到点停掉正在响的闹钟。
     * 用于「响 N 分钟后自动停止」——没人管的时候不会一直响、也不耗电。
     */
    public static void setAutoStop(Context ctx, LocalDateTime when, int alarmId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            PendingIntent pi = PendingIntent.getBroadcast(ctx,
                    requestCode(alarmId, when.toLocalDate(), SEG_AUTOSTOP),
                    autoStopShell(ctx, alarmId, when.toLocalDate()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long millis = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (canScheduleExactAlarms(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pi);
            }
        } catch (Exception ignore) {
        }
    }

    /** 取消「响铃自动停止」闹钟（停止响铃时调用，避免残留）。 */
    public static void cancelAutoStop(Context ctx, int alarmId, LocalDate d) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            PendingIntent pi = PendingIntent.getBroadcast(ctx,
                    requestCode(alarmId, d, SEG_AUTOSTOP),
                    autoStopShell(ctx, alarmId, d),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Exception ignore) {
        }
    }

    private static Intent autoStopShell(Context ctx, int alarmId, LocalDate d) {
        return new Intent(ctx, StopReceiver.class)
                .setAction(StopReceiver.ACTION_AUTO_STOP)
                .setData(Uri.parse("weekdayalarm://autostop/" + alarmId + "/" + d));
    }

    private static void cancelSegment(Context ctx, int alarmId, LocalDate d, int segment) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(alarmId, d, segment),
                pendingIntentShell(ctx, alarmId, d, segment, null),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    private static Intent pendingIntentShell(Context ctx, int alarmId, LocalDate d) {
        return pendingIntentShell(ctx, alarmId, d, SEG_MAIN, null);
    }

    /**
     * 构造用于「匹配 / 创建」PendingIntent 的 intent 壳子。
     * 段偏移不同 → requestCode 不同 → 视为不同的 PendingIntent；
     * 提前通知段还额外换了 data URI，双保险。
     */
    private static Intent pendingIntentShell(Context ctx, int alarmId, LocalDate d,
                                             int segment, String label) {
        String scheme = segment == SEG_PRE_NOTICE ? "prenotice" : "alarm";
        Intent i = new Intent(ctx, segment == SEG_PRE_NOTICE ? PreNoticeReceiver.class : AlarmReceiver.class);
        i.setAction(segment == SEG_PRE_NOTICE
                ? PreNoticeReceiver.ACTION_PRE_NOTICE : ACTION_ALARM);
        i.setData(Uri.parse("weekdayalarm://" + scheme + "/" + alarmId + "/" + d));
        i.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        if (label != null) i.putExtra(AlarmReceiver.EXTRA_LABEL, label);
        return i;
    }

    /** 为某个时刻设置系统级闹钟（setAlarmClock）。单个失败不抛出，避免拖垮整批重排。 */
    public static void setAlarm(Context ctx, LocalDateTime when, int alarmId, String label) {
        setAlarm(ctx, when, alarmId, label, SEG_MAIN);
    }

    private static void setAlarm(Context ctx, LocalDateTime when, int alarmId, String label, int segment) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            PendingIntent pi = alarmPendingIntent(ctx, alarmId, when.toLocalDate(), label, segment);
            long millis = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (canScheduleExactAlarms(ctx)) {
                // v0.23.0：showIntent 之前传 null，点状态栏的闹钟图标没反应；
                // 现在指向主界面，用户点图标就能核对「这闹钟是哪来的」
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(millis, showIntent(ctx)), pi);
            } else {
                // 无精确闹钟权限时降级：1 分钟窗口闹钟，仍会准时触发（分钟级偏差）
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pi);
            }
        } catch (Exception ignore) {
            // ROM 闹钟数量上限等情况下单个 set 会抛 SecurityException；
            // 吞掉它保证其余闹钟与界面不崩（滚动补排最终会覆盖）
        }
    }

    /**
     * 当前是否允许设置精确闹钟。
     * Android 12+ 需要 SCHEDULE_EXACT_ALARM（Android 14 起默认不授予，需用户手动开启），
     * 或 Android 13+ 的 USE_EXACT_ALARM（安装时授予）。
     */
    public static boolean canScheduleExactAlarms(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return true;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true;
        return am.canScheduleExactAlarms();
    }

    /**
     * 状态栏闹钟图标被点击时打开的界面。
     * 全局共用一个 PendingIntent（requestCode 固定 77，与响铃通知的 0/1/2 错开），
     * 避免每次重排 400 个闹钟时重复构造。
     */
    private static PendingIntent cachedShowIntent;

    private static PendingIntent showIntent(Context ctx) {
        if (cachedShowIntent == null) {
            Intent i = new Intent(ctx.getApplicationContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            cachedShowIntent = PendingIntent.getActivity(ctx, 77, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return cachedShowIntent;
    }

    /**
     * 稍后提醒（v0.15.0：间隔与次数均可自定义）：当前时间 + 设定间隔，沿用原闹钟的
     * id 与名称。使用独立的 SNOOZE 段 requestCode——若沿用主闹钟段，任何一次
     * rescheduleAll（响铃后、重启后）的 cancelAll 都会把它连带取消，导致小睡失效。
     */
    public static void snooze(Context ctx, int alarmId, String label) {
        LocalDateTime when = LocalDateTime.now()
                .plusMinutes(Math.max(1, Prefs.getSnoozeMinutes(ctx)));
        setAlarm(ctx, when, alarmId, label, SEG_SNOOZE);
    }

    /**
     * 注册一条「提前通知」闹钟（v0.15.0）：到点推送安静的通知栏提醒，带「关闭这次响铃」操作。
     * 用 setExactAndAllowWhileIdle 而非 setAlarmClock——后者会在状态栏显示闹钟图标，
     * 提前 N 分钟就挂上图标反而打扰。
     *
     * <p><b>编码一律按响铃日 {@code ringDate}，不是提醒时刻 {@code when}</b>（v0.73.3 修复）：
     * 之前 URI / EXTRA_DATE / requestCode 全用 {@code when.toLocalDate()}，而主闹钟登记与
     * {@link #cancelAll} 取消都按响铃日。闹钟 00:30 + 提前 60 分钟时提醒落前一天 23:30，
     * 于是：触发时 {@code isAlarmRegistered} 拿前一天日期查不中、通知被静默丢弃；
     * {@code cancelAll} 也取消不掉它，残留到自然触发（触发时同样被拦下，自愈）。
     * 触发时刻仍用 {@code when}（提醒时刻），只是「身份标识」换成响铃日。
     *
     * <p>提前量最大 999 分钟（16 小时 39 分）&lt; 24h，所以提醒日期最多比响铃日早一天。
     */
    public static void setPreNotice(Context ctx, LocalDateTime when, int alarmId,
                                    String label, int minutes, LocalDate ringDate) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            Intent i = preNoticeIntent(ctx, alarmId, label, ringDate, minutes,
                    a_timeText(alarmId, ctx));
            PendingIntent pi = PendingIntent.getBroadcast(ctx,
                    preNoticeRequestCode(alarmId, ringDate), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long millis = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (canScheduleExactAlarms(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pi);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 提前通知闹钟的 Intent（v0.73.3 抽出，供守门测试断言编码）。
     *
     * <p>「身份」三件套——data URI、{@code EXTRA_DATE}、requestCode——一律按响铃日
     * {@code ringDate} 编码，与主闹钟登记、{@code cancelAll} 取消同一套日期。
     * 回归测试见 {@code BugfixRegressionTest.preNoticeIntent_encodesRingDayNotPreDay}。
     */
    static Intent preNoticeIntent(Context ctx, int alarmId, String label,
                                  LocalDate ringDate, int minutes, String timeText) {
        return new Intent(ctx, PreNoticeReceiver.class)
                .setAction(PreNoticeReceiver.ACTION_PRE_NOTICE)
                .setData(Uri.parse("weekdayalarm://prenotice/" + alarmId + "/" + ringDate))
                .putExtra(PreNoticeReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(PreNoticeReceiver.EXTRA_LABEL, label)
                .putExtra(PreNoticeReceiver.EXTRA_DATE, ringDate.toString())
                .putExtra(PreNoticeReceiver.EXTRA_MINUTES, minutes)
                .putExtra(PreNoticeReceiver.EXTRA_TIME_TEXT, timeText);
    }

    /** 查闹钟显示用时间文本（HH:mm），取不到就用空串。 */
    private static String a_timeText(int alarmId, Context ctx) {
        for (Alarm a : Prefs.getAlarms(ctx)) {
            if (a.id == alarmId) return a.timeText();
        }
        return "";
    }

    /**
     * 取消所有已调度的闹钟（主实例 + 提前通知）。
     *
     * 注意不含「稍后提醒」与「自动停止」段——这两类属于一次响铃的临时状态，
     * 必须跨 rescheduleAll 存活（响铃后立即重排时不能把小睡顺手取消）。
     * 需要连它们一起清的场景见 {@link #cancelAllIncludingTransient}。
     */
    public static void cancelAll(Context ctx) {
        // 旧编码清理只在升级后做一次：每次重排都对 400 个日期多查两轮 PendingIntent
        // 会白花 800 次 IPC，而旧实例一旦清完就不会再出现
        boolean needLegacyClean = !Prefs.isLegacyAlarmsCleaned(ctx);
        // v0.87.2：URI scheme 从 daxz:// 改成 weekdayalarm://，旧实例要单独清一次
        boolean needSchemeClean = !Prefs.isLegacySchemeCleaned(ctx);
        if (needSchemeClean) cancelLegacyBedtime(ctx);
        for (String s : Prefs.getScheduledDates(ctx)) {
            try {
                int sep = s.indexOf(':');
                int alarmId;
                LocalDate d;
                if (sep <= 0) {
                    // v0.11.0 之前的旧记录格式是 "yyyy-MM-dd"（无 alarmId 前缀）。
                    // 旧版 requestCode 编码 = 0 * 100000 + 日偏移，与 alarmId=0 完全一致，
                    // 这里按 alarmId=0 取消，避免升级后旧闹钟残留在系统里重复触发。
                    alarmId = 0;
                    d = LocalDate.parse(s);
                } else {
                    alarmId = Integer.parseInt(s.substring(0, sep));
                    d = LocalDate.parse(s.substring(sep + 1));
                }
                cancelAlarm(ctx, alarmId, d);
                cancelPreNotice(ctx, alarmId, d);   // 提前通知一起清，避免残留
                // v0.23.0：顺带按旧编码（相对今天的偏移）取消一次。旧版本注册的实例
                // 用的是旧 requestCode，升级后若不清，会与新版实例并存并重复响铃。
                if (needLegacyClean) cancelLegacy(ctx, alarmId, d);
                if (needSchemeClean) cancelLegacyScheme(ctx, alarmId, d);
            } catch (Exception ignore) {
            }
        }
        Prefs.setScheduledDates(ctx, new HashSet<>());
        if (needLegacyClean) Prefs.setLegacyAlarmsCleaned(ctx, true);
        if (needSchemeClean) Prefs.setLegacySchemeCleaned(ctx, true);
    }

    /**
     * 「彻底停掉」的场景（v0.56.3 起：删到没有启用的闹钟）：在主实例之外，
     * 连稍后提醒与自动停止一起清掉。否则会出现「点了小睡 → 删掉闹钟 → 5 分钟后照样响」。
     */
    public static void cancelAllIncludingTransient(Context ctx) {
        cancelAll(ctx);
        LocalDate today = LocalDate.now();
        for (Alarm a : Prefs.getAlarms(ctx)) {
            // 小睡可能落在今天或明天（跨零点按的「稍后提醒」）
            cancelSnooze(ctx, a.id, today);
            cancelSnooze(ctx, a.id, today.plusDays(1));
            cancelAutoStop(ctx, a.id, today);
            cancelAutoStop(ctx, a.id, today.plusDays(1));
            // 提前提醒是常驻通知（v0.31.2），彻底停掉后它说的「即将响铃」已经不成立，
            // 一并撤掉，否则会一直挂在通知栏里
            try {
                NotificationManagerCompat.from(ctx)
                        .cancel(PreNoticeReceiver.NOTIF_ID_BASE + a.id);
            } catch (Exception ignore) {
            }
        }
    }

    /** 按 v0.23.0 之前的旧编码再取消一次（升级清理用，打不中就静默跳过）。 */
    private static void cancelLegacy(Context ctx, int alarmId, LocalDate d) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int legacySlot = legacyDayOffset(d, LocalDate.now());
        if (legacySlot == dateSlot(d)) return;   // 新旧编码恰好相同，不必重复取消
        try {
            PendingIntent pi = PendingIntent.getBroadcast(ctx,
                    alarmId * REQUEST_BASE + SEG_MAIN + legacySlot,
                    pendingIntentShell(ctx, alarmId, d, SEG_MAIN, null),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
            PendingIntent pre = PendingIntent.getBroadcast(ctx,
                    alarmId * REQUEST_BASE + SEG_PRE_NOTICE + legacySlot,
                    pendingIntentShell(ctx, alarmId, d, SEG_PRE_NOTICE, null),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pre != null) {
                am.cancel(pre);
                pre.cancel();
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 按 v0.87.2 之前的旧 scheme 再取消一次（升级清理用，打不中就静默跳过）。
     *
     * <p>与 {@link #cancelLegacy} 同一个套路：{@code FLAG_NO_CREATE} 只查不建，
     * 查不到直接跳过——所以「全新安装」时这几行是纯浪费的 3 次空查询，代价可忽略；
     * 而「升级一次」就能把旧实例清干净。
     */
    private static void cancelLegacyScheme(Context ctx, int alarmId, LocalDate d) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            cancelLegacyOne(ctx, am, requestCode(alarmId, d, SEG_MAIN),
                    new Intent(ctx, AlarmReceiver.class)
                            .setAction(ACTION_ALARM)
                            .setData(Uri.parse(LEGACY_SCHEME + "alarm/" + alarmId + "/" + d)));
            cancelLegacyOne(ctx, am, requestCode(alarmId, d, SEG_PRE_NOTICE),
                    new Intent(ctx, PreNoticeReceiver.class)
                            .setAction(PreNoticeReceiver.ACTION_PRE_NOTICE)
                            .setData(Uri.parse(LEGACY_SCHEME + "prenotice/" + alarmId + "/" + d)));
            cancelLegacyOne(ctx, am, requestCode(alarmId, d, SEG_AUTOSTOP),
                    new Intent(ctx, StopReceiver.class)
                            .setAction(StopReceiver.ACTION_AUTO_STOP)
                            .setData(Uri.parse(LEGACY_SCHEME + "autostop/" + alarmId + "/" + d)));
        } catch (Exception ignore) {
        }
    }

    /** 睡前提醒只有一个实例（没有日期维度），在日期循环之外单独清一次。 */
    private static void cancelLegacyBedtime(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            cancelLegacyOne(ctx, am, BedtimeReminder.REQUEST_CODE,
                    new Intent(ctx, BedtimeReceiver.class)
                            .setAction(BedtimeReceiver.ACTION_BEDTIME)
                            .setData(Uri.parse(LEGACY_SCHEME + "bedtime")));
        } catch (Exception ignore) {
        }
    }

    /** 查到就取消，查不到什么也不做。 */
    private static void cancelLegacyOne(Context ctx, AlarmManager am, int requestCode,
                                        Intent intent) {
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    /**
     * 重算并重设全部闹钟（先取消旧的）。
     *
     * 收集所有启用闹钟在未来 horizon 内的全部候选实例，按时间从近到远排序后
     * 最多注册 MAX_REGISTERED_ALARMS 个——防止越过 ROM 的 500 闹钟硬限导致
     * SecurityException 闪退；被截断的远端实例由响铃/重启后的滚动补排覆盖。
     *
     * @return 实际注册的闹钟数
     */
    /** 重排/取消互斥锁：响铃广播会从后台线程重排，用户也可能同时在改设置。 */
    private static final Object SCHEDULE_LOCK = new Object();

    public static int rescheduleAll(Context ctx) {
        synchronized (SCHEDULE_LOCK) {
            return rescheduleAllLocked(ctx);
        }
    }

    private static int rescheduleAllLocked(Context ctx) {
        cancelAll(ctx);

        // v0.44.0：排程前先结算——过期的「仅一次」若还挂着 enabled=true，
        // 排程表里虽然排不到它，但列表与统计口径会一直当它有效
        List<Alarm> alarms = Prefs.getAlarmsSettled(ctx);
        if (alarms.isEmpty()) return 0;

        int horizon = Prefs.getHorizonDays(ctx);
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        // v0.87.5：先清过期的跳过标记，再让下面的候选循环认它们——
        // 「这次不响」只撤实例、不记标记的话，这一次重排就会把取消原样撤销
        Prefs.pruneSkipOnce(ctx, today);
        Set<String> skipOnce = Prefs.getSkipOnce(ctx);
        // v0.23.0 性能：请假 / 加班集合只读一次。旧版在「闹钟数 × 天数」的双层循环里
        // 逐日调用 Prefs.isVacation / isOvertime，每次都会把整个 StringSet 拷一份
        // （3 个闹钟 × 365 天 × 2 次 ≈ 2000 次拷贝），是改设置时卡顿的主因之一。
        Set<LocalDate> vacations = Prefs.getVacationDates(ctx);
        Set<LocalDate> overtimes = Prefs.getOvertimeDates(ctx);

        // 收集候选：[0]=Alarm [1]=触发时刻；按时间排序保证最近的优先注册
        List<Object[]> candidates = new ArrayList<>();
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            LocalTime alarmTime = LocalTime.of(a.hour, a.minute);
            for (int i = 0; i < horizon; i++) {
                LocalDate d = today.plusDays(i);
                // v0.87.5：「这次不响」跳过的实例不许复活。重排只认列表开关，
                // 没有这笔标记，点完到下一次响铃之间就白点了（真机实测）
                if (skipOnce.contains(a.id + ":" + d)) continue;
                // 个人请假：当天不响——但一次性闹钟豁免，它是「这天要叫我」，
                // 不是「按作息排的班」（见 Alarm.ignoresVacation）
                if (!a.ignoresVacation() && vacations.contains(d)) continue;
                // 个人加班（v0.13.0）：休息日标记加班后「仅上班日」闹钟当天照响
                if (!a.appliesTo(d, rule, overtimes.contains(d))) continue;
                LocalDateTime when = LocalDateTime.of(d, alarmTime);
                if (!when.isAfter(now)) continue;   // 今天已过点就跳过
                candidates.add(new Object[]{a, when});
            }
        }
        candidates.sort((x, y) -> ((LocalDateTime) x[1]).compareTo((LocalDateTime) y[1]));

        Set<String> scheduled = new HashSet<>();
        // 每个响铃实例占 1 个系统闹钟；开启提前通知时再多占 1 个，故主闹钟上限减半，
        // 保证「主闹钟 + 提前通知」总数始终不超过 MAX_REGISTERED_ALARMS（ROM 硬限）。
        int preNotice = Prefs.getPreNoticeMinutes(ctx);
        int limit = Math.min(candidates.size(),
                preNotice > 0 ? MAX_REGISTERED_ALARMS / 2 : MAX_REGISTERED_ALARMS);
        LocalDateTime now2 = LocalDateTime.now();
        for (int i = 0; i < limit; i++) {
            Alarm a = (Alarm) candidates.get(i)[0];
            LocalDateTime when = (LocalDateTime) candidates.get(i)[1];
            setAlarm(ctx, when, a.id, a.label);
            scheduled.add(a.id + ":" + when.toLocalDate());
            if (preNotice > 0) {
                LocalDateTime pre = when.minusMinutes(preNotice);
                if (pre.isAfter(now2)) {   // 提前时刻已过就别排了，免得补一条过期通知
                    // ringDate 用响铃日：提前提醒的「身份」（requestCode / URI / EXTRA_DATE）
                    // 必须与主闹钟登记、cancelAll 取消同一套日期，跨午夜才不会失效（v0.73.3）
                    setPreNotice(ctx, pre, a.id, a.label, preNotice, when.toLocalDate());
                }
            }
        }
        // v0.31.2：在提前量窗口内才设的闹钟（如 14:31 设 15:31 的闹钟、提前 1 小时），
        // 算出来的提醒时刻已经过去，上面「pre 已过就别排了」会直接跳过，
        // 这一次就永远收不到提前通知。这里补发。
        //
        // v0.32.1：补发范围从「第一个候选」扩到「所有已进窗口的候选」，并且顺带做一次
        // 通知栏对账——把「挂着但已经不该提醒」的撤掉。之前单个闹钟关掉后，
        // 通知栏里那条「即将响铃」会一直留着，点进去是个已停用的闹钟。
        // candidates 按响铃时刻升序，pre 也升序，所以 pre 一旦还在未来就可以停。
        List<Object[]> due = new ArrayList<>();
        if (preNotice > 0) {
            for (Object[] c : candidates) {
                LocalDateTime when = (LocalDateTime) c[1];
                if (when.minusMinutes(preNotice).isAfter(now2)) break;
                due.add(c);
            }
        }
        Set<Integer> keep = new HashSet<>();
        for (Object[] c : due) {
            keep.add(PreNoticeReceiver.NOTIF_ID_BASE + ((Alarm) c[0]).id);
        }
        // 撤掉不再需要的（关掉的闹钟、已响过的、提前提醒被关掉的）
        dropPreNoticesExcept(ctx, keep);
        // 该提醒的重新发一遍：同 id 覆盖更新，配合 setOnlyAlertOnce 不会重复弹横幅，
        // 但能把旧版本留下的样式（没有按钮的那种）换成新版
        for (Object[] c : due) {
            Alarm a = (Alarm) c[0];
            LocalDateTime when = (LocalDateTime) c[1];
            // post() 内部会再确认「这个闹钟还在系统里登记着」，已停用的不会因此复活
            PreNoticeReceiver.post(ctx, a.id, a.label, when.toLocalDate(), preNotice,
                    TimeText.clock(when.getHour(), when.getMinute()));
        }
        Prefs.setScheduledDates(ctx, scheduled);
        Prefs.pruneSnoozeCounts(ctx);   // 顺手清掉昨天及以前的稍后提醒计数
        return scheduled.size();
    }

    /**
     * 清掉旧版本发出的提前提醒通知（v0.31.3）。
     *
     * <p>旧版的 action 图标传了 0，MIUI / 澎湃不渲染按钮，那条通知永远长不出「关闭闹钟」。
     * 而重排时如果看到「已经挂着一条」就跳过补发，升级之后按钮依然不出现。
     * 升级后第一次启动先把旧的清掉，让重排重新发一条带图标的。
     */
    static void dropStalePreNotices(Context ctx) {
        dropPreNoticesExcept(ctx, new HashSet<Integer>());
    }

    /**
     * 只保留 {@code keep} 里列出的那些提前提醒，其余全部撤掉（v0.32.1）。
     *
     * <p>重排时用它做一次「通知栏对账」：闹钟被关掉、已经响过、或者提前提醒整体关闭之后，
     * 通知栏里不该还留着「即将响铃」——之前只有响铃和彻底停掉会撤，单个闹钟关掉不撤。
     */
    private static void dropPreNoticesExcept(Context ctx, Set<Integer> keep) {
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                int id = s.getId();
                // 提前提醒的 id 段：NOTIF_ID_BASE + alarmId（alarmId 是自增小整数）
                if (id >= PreNoticeReceiver.NOTIF_ID_BASE
                        && id < PreNoticeReceiver.NOTIF_ID_BASE + 1000
                        && !keep.contains(id)) {
                    nm.cancel(id);
                }
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 未来 horizon 内将响的闹钟候选总数（未截断的理论值）。
     * 与 countScheduled 的区别：本方法统计全部启用闹钟的全部适用日，
     * 用于判断是否发生了 MAX_REGISTERED_ALARMS 截断，以便 UI 提示用户。
     */
    public static int pendingAlarmCount(Context ctx) {
        return countCandidates(ctx);
    }

    /**
     * 未来 horizon 内「理论上会响」的实例总数。
     * countScheduled / pendingAlarmCount 共用同一实现——两者此前各写了一遍完全相同的
     * 双层循环，改一处忘另一处就会让界面数字与实际排程对不上。
     */
    private static int countCandidates(Context ctx) {
        return countCandidates(ctx, Prefs.getAlarms(ctx));
    }

    /**
     * 同上，但用调用方已经读好的列表。
     *
     * <p>v0.43.0：首页一次刷新要算「下次响铃 / 实例总数 / 已登记闹钟」三样，
     * 旧写法每样都重新读一遍 Prefs。而 {@code Prefs.getAlarms} 现在会顺手
     * 关闭过期的一次性闹钟（可能写盘），三次读就是三次序列化 + 两次多余判断。
     * 传进来一次，三处共用。
     */
    static int countCandidates(Context ctx, List<Alarm> alarms) {
        if (alarms == null || alarms.isEmpty()) return 0;
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int horizon = Prefs.getHorizonDays(ctx);
        Set<LocalDate> vacations = Prefs.getVacationDates(ctx);
        Set<LocalDate> overtimes = Prefs.getOvertimeDates(ctx);
        int n = 0;
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            LocalTime alarmTime = LocalTime.of(a.hour, a.minute);
            for (int i = 0; i < horizon; i++) {
                LocalDate d = today.plusDays(i);
                if (!a.ignoresVacation() && vacations.contains(d)) continue;
                if (!a.appliesTo(d, rule, overtimes.contains(d))) continue;
                if (LocalDateTime.of(d, alarmTime).isAfter(now)) n++;
            }
        }
        return n;
    }

    /**
     * 是否允许使用全屏意图（锁屏上自动弹出闹钟界面）。
     * Android 14 起该权限默认只授予通话/闹钟类应用，其他应用需用户在设置中手动开启；
     * MIUI 等 ROM 还额外受「后台弹出界面」权限限制。
     */
    public static boolean canUseFullScreenIntent(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            android.app.NotificationManager nm =
                    ctx.getSystemService(android.app.NotificationManager.class);
            return nm != null && nm.canUseFullScreenIntent();
        }
        return true;
    }

    /** 统计未来窗口内将响的闹钟总数（含今天未过点的），供 UI 展示。 */
    public static int countScheduled(Context ctx) {
        return countCandidates(ctx);
    }

    /** 同上，复用调用方已读好的列表（首页刷新用，省掉重复读 Prefs）。 */
    public static int countScheduled(Context ctx, List<Alarm> alarms) {
        return countCandidates(ctx, alarms);
    }

    /** 检查某闹钟在某天的实例是否仍在系统中登记（FLAG_NO_CREATE 不会新建，只探测）。 */
    public static boolean isAlarmRegistered(Context ctx, int alarmId, LocalDate d) {
        return isSegmentRegistered(ctx, alarmId, d, SEG_MAIN);
    }

    /** 检查「提前通知」闹钟是否仍在系统中登记（v0.15.0）。 */
    public static boolean isPreNoticeRegistered(Context ctx, int alarmId, LocalDate d) {
        return isSegmentRegistered(ctx, alarmId, d, SEG_PRE_NOTICE);
    }

    private static boolean isSegmentRegistered(Context ctx, int alarmId, LocalDate d, int segment) {
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(alarmId, d, segment),
                pendingIntentShell(ctx, alarmId, d, segment, null),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        return pi != null;
    }

    /**
     * 下一个仍被系统登记、且尚未到点的闹钟；没有则返回 null。
     *
     * v0.23.0 性能：先按时间升序排出候选，再逐个查登记状态，**第一个命中即返回**。
     * 旧版对每个候选都做一次 PendingIntent 查询（每次都是跨进程 IPC），
     * 3 个闹钟 × 365 天 ≈ 1000 次 IPC，主界面每次刷新都要跑一遍，是明显的卡顿来源。
     */
    public static NextAlarm nextRegisteredAlarm(Context ctx) {
        return nextRegisteredAlarm(ctx, Prefs.getAlarms(ctx));
    }

    /** 同上，复用调用方已读好的列表（v0.43.0）。 */
    public static NextAlarm nextRegisteredAlarm(Context ctx, List<Alarm> alarms) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int horizon = Prefs.getHorizonDays(ctx);
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        Set<LocalDate> vacations = Prefs.getVacationDates(ctx);
        Set<LocalDate> overtimes = Prefs.getOvertimeDates(ctx);

        List<Object[]> candidates = new ArrayList<>();
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            LocalTime alarmTime = LocalTime.of(a.hour, a.minute);
            for (int i = 0; i < horizon; i++) {
                LocalDate d = today.plusDays(i);
                LocalDateTime when = LocalDateTime.of(d, alarmTime);
                if (!when.isAfter(now)) continue;
                if (!a.ignoresVacation() && vacations.contains(d)) continue;   // 请假：跳过
                if (!a.appliesTo(d, rule, overtimes.contains(d))) continue;
                candidates.add(new Object[]{a, when});
            }
        }
        candidates.sort((x, y) -> ((LocalDateTime) x[1]).compareTo((LocalDateTime) y[1]));
        for (Object[] c : candidates) {
            Alarm a = (Alarm) c[0];
            LocalDateTime when = (LocalDateTime) c[1];
            if (isAlarmRegistered(ctx, a.id, when.toLocalDate())) {
                return new NextAlarm(when, a.label, a.id);
            }
        }
        return null;
    }

    /**
     * 下一个「该响」的时刻（v0.30.0）：纯计算，不查系统登记状态。
     *
     * <p>与 {@link #nextRegisteredAlarm} 的分工：那个是「系统里真的排上了没有」的自检，
     * 每次都要做 PendingIntent 探测；这个是「按规则本该什么时候响」，用来在首页显示
     * 倒计时和「下次」提示。系统里没有登记时，用户仍然想知道
     * 「下一次是几点」——所以调用方自己决定要不要用。
     */
    public static NextAlarm nextRing(Context ctx, LocalDateTime from) {
        return nextRing(Prefs.getAlarms(ctx), Prefs.getRule(ctx),
                Prefs.getVacationDates(ctx), Prefs.getOvertimeDates(ctx),
                from, Prefs.getHorizonDays(ctx));
    }

    /**
     * 见 {@link #nextRing(Context, LocalDateTime)}。抽成静态是为了单测能固定「现在」。
     *
     * <p>只按「闹钟启用 + 规则命中 + 当天没请假」筛选，不看分钟级以下的
     * 偏移：闹钟在 hh:mm:00 触发，{@code from} 正好等于该时刻时算已过（返回下一个）。
     */
    static NextAlarm nextRing(List<Alarm> alarms, WeekSchedule.Rule rule,
                              Set<LocalDate> vacations, Set<LocalDate> overtimes,
                              LocalDateTime from, int days) {
        NextAlarm best = null;
        LocalDate start = from.toLocalDate();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            // 请假判断原本在闹钟循环之外（整天跳过）。一次性闹钟要豁免，
            // 就只能挪进循环里逐支判断——否则首页会说「明天不响」，而它其实会响
            boolean vacation = vacations.contains(d);
            boolean overtime = overtimes.contains(d);
            for (Alarm a : alarms) {
                if (!a.enabled) continue;
                if (vacation && !a.ignoresVacation()) continue;
                if (!a.appliesTo(d, rule, overtime)) continue;
                LocalDateTime when = LocalDateTime.of(d, LocalTime.of(a.hour, a.minute));
                if (!when.isAfter(from)) continue;
                if (best == null || when.isBefore(best.when)) {
                    best = new NextAlarm(when, a.label, a.id);
                }
            }
            // 天是升序的：当天一旦有候选，它就是全局最近的，后面的天只会更晚
            if (best != null) break;
        }
        return best;
    }

    /** 下一个已登记闹钟的信息（时间 + 名称），供 UI 自检展示。 */
    public static class NextAlarm {
        public final LocalDateTime when;
        public final String label;
        public final int alarmId;

        NextAlarm(LocalDateTime when, String label, int alarmId) {
            this.when = when;
            this.label = label;
            this.alarmId = alarmId;
        }
    }
}
