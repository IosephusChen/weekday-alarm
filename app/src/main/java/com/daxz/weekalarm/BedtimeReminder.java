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

        String title;
        String text;
        if (vacation) {
            title = "明天已请假";
            text = "明天不用早起，闹钟不会响";
        } else if (!workday) {
            title = "明天休息";
            text = "明天不用早起，闹钟不会响，放心睡";
        } else if (hasAlarm) {
            title = "明天要上班";
            text = "明早 " + String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                    + " " + label + " 会响，早点睡";
        } else {
            title = "明天要上班";
            text = "明天没有会响的闹钟，记得加一个";
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
        refreshIfShown(ctx, computeModel(ctx, LocalDate.now().plusDays(1)));
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
            try {
                nm.cancel(NOTIF_ID);
            } catch (Throwable ignore) {
            }
            return;
        }
        BedtimeReceiver.repost(ctx, m);
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
