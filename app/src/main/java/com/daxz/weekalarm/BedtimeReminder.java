package com.daxz.weekalarm;

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
import java.util.List;
import java.util.Locale;

/**
 * 睡前提醒（v1.20）：「明天要早起吗」。
 *
 * 在用户设定的时刻（默认关闭，可选 21:30 ~ 23:00 或自定义）发一条安静的通知，
 * 提前说明明早到底会不会响、几点响——避免两种常见的翻车：
 * ① 明天其实要上班，却以为闹钟不会响（大小周记错 / 忘了调休补班）；
 * ② 明天放假，闹钟却照样把人吵醒（忘了关）。
 *
 * 通知里带「明天请假」按钮：一键把明天标为请假并取消明天的全部闹钟，
 * 比「打开 App → 翻日历 → 点明天 → 标记请假」快得多。
 *
 * 调度策略：只注册**下一条**（固定 requestCode），触发后再由
 * {@link BedtimeReceiver} 续订下一天。这样无论隔多久没打开 App，
 * 提醒都会一天一天滚动下去，也不会占用 ROM 的闹钟名额（只占 1 个）。
 */
public final class BedtimeReminder {

    /**
     * 固定 requestCode：全局只有一条睡前提醒。
     * 999001 落在「闹钟 id × 100000」各段的空档里（最大段 SEG_AUTOSTOP 到 998999），
     * 即便未来某天 PendingIntent 的身份判断变化，也不会和闹钟互相顶掉。
     */
    public static final int REQUEST_CODE = 999_001;

    /** 通知 id（取消 / 覆盖同一条）。 */
    public static final int NOTIF_ID = 3001;

    /** 午夜刷新的 PendingIntent（与提醒本身分开，避免互相顶掉）。 */
    public static final int MIDNIGHT_REQUEST_CODE = 999_002;

    /**
     * 这条提醒在第二天几点之后彻底失效（v1.48.0）。
     *
     * <p>与通知自带的过期时间同一个时刻：05:00 是这一夜结束、起床闹钟开始响的时段。
     * 在此之前跨天要改口成「今天」，之后就该消失。
     */
    static final LocalTime MORNING_CUTOFF = LocalTime.of(5, 0);

    private BedtimeReminder() {
    }

    /** 睡前提醒的展示模型（纯数据，便于单测）。 */
    public static final class Model {
        public final LocalDate tomorrow;
        public final boolean vacation;   // 明天被标记为个人请假
        public final boolean workday;    // 明天实际要上班
        public final boolean hasAlarm;   // 明天有会响的闹钟
        public final String alarmLabel;
        public final int alarmHour;
        public final int alarmMinute;
        /** 是否应该发这条通知（「仅在明天上班时提醒」且明天休息 → false）。 */
        public final boolean notify;
        public final String title;
        public final String text;

        Model(LocalDate tomorrow, boolean vacation, boolean workday, boolean hasAlarm,
              String alarmLabel, int alarmHour, int alarmMinute,
              boolean notify, String title, String text) {
            this.tomorrow = tomorrow;
            this.vacation = vacation;
            this.workday = workday;
            this.hasAlarm = hasAlarm;
            this.alarmLabel = alarmLabel;
            this.alarmHour = alarmHour;
            this.alarmMinute = alarmMinute;
            this.notify = notify;
            this.title = title;
            this.text = text;
        }
    }

    /** 计算明天（或指定日期）的睡前提醒内容。 */
    public static Model computeModel(Context ctx, LocalDate tomorrow) {
        boolean vacation = Prefs.isVacation(ctx, tomorrow);
        boolean workday = !vacation && ScheduleManager.isEffectiveWorkday(ctx, tomorrow);

        // 明天的第一个闹钟：只算启用的、且当天适用的
        List<Alarm> alarms = Prefs.getAlarmsSettled(ctx);
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        boolean overtime = Prefs.isOvertime(ctx, tomorrow);
        Alarm first = null;
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            if (!a.appliesTo(tomorrow, rule, overtime)) continue;
            if (first == null || a.hour * 60 + a.minute < first.hour * 60 + first.minute) {
                first = a;
            }
        }
        boolean hasAlarm = first != null && !vacation;
        String label = first != null ? first.label : "";
        int hour = first != null ? first.hour : 0;
        int minute = first != null ? first.minute : 0;

        boolean notify = Prefs.isBedtimeWorkdayOnly(ctx) ? workday : true;

        // v1.48.0：相对词不能写死。22:00 发的时候说「明天」是对的；跨过午夜之后
        // 同一条通知指的已经是「今天」，再写「明天 07:30」就错了一天。
        // 词由目标日期与今天的关系决定，所以凌晨重发一次就能自动改口。
        boolean isToday = tomorrow.equals(LocalDate.now());
        String day = isToday ? "今天" : "明天";
        String morning = isToday ? "今天早上" : "明早";

        String title;
        String text;
        if (vacation) {
            title = day + "已请假";
            text = day + "不用早起，闹钟不会响";
        } else if (!workday) {
            title = day + "休息";
            text = day + "不用早起，闹钟不会响，放心睡";
        } else if (hasAlarm) {
            title = day + "要上班";
            text = morning + " " + String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                    + " " + label + " 会响，早点睡";
        } else {
            title = day + "要上班";
            text = day + "没有会响的闹钟，记得加一个";
        }
        return new Model(tomorrow, vacation, workday, hasAlarm, label, hour, minute,
                notify, title, text);
    }

    /** 下一次触发时刻；未开启返回 null。恰好等于当前时刻时算到明天（避免重复触发）。 */
    public static LocalDateTime nextTrigger(Context ctx, LocalDateTime now) {
        int minutes = Prefs.getBedtimeMinutes(ctx);
        if (minutes == Prefs.BEDTIME_OFF) return null;
        LocalTime t = LocalTime.of(minutes / 60, minutes % 60);
        LocalDateTime todayAt = LocalDateTime.of(now.toLocalDate(), t);
        return todayAt.isAfter(now) ? todayAt : todayAt.plusDays(1);
    }

    /**
     * 通知还挂在通知栏时，按当前状态把它刷成最新内容（v1.46.7）。
     *
     * <p>问题场景：22:00 那条提醒写着「明天没有会响的闹钟」，用户随后在 App 里加了闹钟，
     * 通知仍停在通知栏上说着旧话。
     *
     * <p>只在通知**确实还在**时才动手：已经划掉或从没发过，就不该凭空冒出来。
     * 重发同一个通知 id 并设 {@code setOnlyAlertOnce}，只换内容、不再响一次。
     */
    public static void refreshIfShown(Context ctx) {
        if (ctx == null) return;
        refreshIfShownAt(ctx, LocalDate.now(), LocalTime.now());
    }

    /**
     * 刷新时按「现在是哪一天」决定该说什么（v1.48.0）。
     *
     * <p>三种情况：
     * <ul>
     *   <li>当天发的（22:00 之后）：说的仍是「明天」，照常按明天重算；</li>
     *   <li>跨天但还在凌晨（05:00 前）：昨晚的「明天」就是今天，按**今天**重算，
     *       文案自动从「明天 07:30」变成「今天早上 07:30」；</li>
     *   <li>跨天且已过 05:00：这条提醒没有意义了，撤掉（正常情况早被系统按过期时间移除，
     *       这里防的是不守 timeoutAfter 的 ROM）。</li>
     * </ul>
     *
     * <p>拆出 today/now 两个参数是为了单测能固定时刻，不必去改系统时钟。
     */
    static void refreshIfShownAt(Context ctx, LocalDate today, LocalTime now) {
        String postedDay = Prefs.getBedtimeNoticeDay(ctx);
        boolean stale = postedDay != null && !postedDay.equals(today.toString());
        if (stale && !now.isBefore(MORNING_CUTOFF)) {
            cancelNotice(ctx);
            return;
        }
        refreshIfShown(ctx, computeModel(ctx, stale ? today : today.plusDays(1)));
    }

    /** 用给定内容刷新（便于测试；生产走上面那个按当天实况计算的版本）。 */
    static void refreshIfShown(Context ctx, Model m) {
        if (ctx == null || m == null) return;
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        boolean shown = false;
        try {
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() == NOTIF_ID) {
                    shown = true;
                    break;
                }
            }
        } catch (Throwable ignore) {
            return;     // 个别 ROM 限制查询时宁可不改，也不要误发一条新通知
        }
        if (!shown) return;

        if (!m.notify) {
            cancelNotice(ctx);
            return;
        }
        BedtimeReceiver.repost(ctx, m);
    }

    /**
     * 撤掉通知栏上的睡前提醒（v1.47.9）。
     *
     * <p>三个调用点：跨天打开 App、状态变成不该提醒、起床闹钟开始响——
     * 最后那个最自然：响铃就是这一夜结束了，昨晚那条提醒的任务已经完成。
     */
    public static void cancelNotice(Context ctx) {
        if (ctx == null) return;
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID);
        } catch (Throwable ignore) {
        }
        cancelMidnightRefresh(ctx);
    }

    /**
     * 排一次午夜刷新（v1.48.0）：跨过 00:00 时把文案里的「明天」改成「今天」。
     *
     * <p>为什么不靠用户打开 App：这段时间用户在睡觉，通知就挂在锁屏上。
     * 00:00 前后那句「明天 07:30」会突然变成错的——差一天，而它正对着用户的脸。
     *
     * <p>只排一次、触发后再由通知路径重排；没发通知时不会排。
     */
    static void scheduleMidnightRefresh(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long ms = LocalDate.now().plusDays(1).atStartOfDay()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            PendingIntent pi = midnightIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT);
            if (ScheduleManager.canScheduleExactAlarms(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
            } else {
                // 没有精确闹钟权限就放宽 15 分钟：文案晚一刻钟改口，比不改好
                am.setWindow(AlarmManager.RTC_WAKEUP, ms, 15 * 60_000L, pi);
            }
        } catch (Exception ignore) {
            // ROM 限制不影响主流程：最坏情况是文案停在「明天」，05:00 通知照样消失
        }
    }

    static void cancelMidnightRefresh(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = midnightIntent(ctx, PendingIntent.FLAG_NO_CREATE);
            if (am != null && pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Exception ignore) {
        }
    }

    private static PendingIntent midnightIntent(Context ctx, int flags) {
        Intent i = new Intent(ctx, BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_MIDNIGHT);
        return PendingIntent.getBroadcast(ctx, MIDNIGHT_REQUEST_CODE, i,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 重新注册下一条睡前提醒（关闭状态则取消已有的）。
     * 幂等：重复调用只会覆盖同一条 PendingIntent，不会堆积。
     */
    public static void reschedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        LocalDateTime next = nextTrigger(ctx, LocalDateTime.now());
        try {
            if (next == null) {
                cancel(ctx);
                return;
            }
            PendingIntent pi = pendingIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT);
            long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (ScheduleManager.canScheduleExactAlarms(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi);
            } else {
                // 无精确闹钟权限时降级成 1 分钟窗口：睡前提醒差一两分钟无所谓
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pi);
            }
        } catch (Exception ignore) {
            // ROM 限制 / 权限异常都不能影响主流程
        }
    }

    /** 取消已注册的睡前提醒。 */
    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            PendingIntent pi = pendingIntent(ctx, PendingIntent.FLAG_NO_CREATE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Exception ignore) {
        }
    }

    private static PendingIntent pendingIntent(Context ctx, int flags) {
        Intent i = new Intent(ctx, BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_BEDTIME)
                .setData(Uri.parse("daxz://bedtime"));
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 「22:00」；关闭时返回「关闭」。 */
    public static String timeText(int minutesOfDay) {
        if (minutesOfDay == Prefs.BEDTIME_OFF) return "关闭";
        return String.format(Locale.getDefault(), "%02d:%02d",
                minutesOfDay / 60, minutesOfDay % 60);
    }
}
