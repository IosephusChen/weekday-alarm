package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 响铃前的通知栏提醒（v0.15.0）。
 *
 * 在闹钟响铃前 N 分钟推送一条**安静**的通知（独立低优先级渠道，不响铃不震动），
 * 通知带一个「关闭这次响铃」操作：提前知道闹钟要响，也可以顺手把它关掉。
 *
 * 触发来源：{@link ScheduleManager#rescheduleAll} 为每个已登记的主实例配对注册一条
 * 提前通知闹钟（独立的 PRE_NOTICE requestCode 段，见该类注释）。
 */
public class PreNoticeReceiver extends BroadcastReceiver {

    public static final String ACTION_PRE_NOTICE = "io.github.iosephuschen.weekdayalarm.PRE_NOTICE";
    public static final String ACTION_CANCEL_THIS = "io.github.iosephuschen.weekdayalarm.CANCEL_THIS";
    public static final String EXTRA_ALARM_ID = AlarmReceiver.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = AlarmReceiver.EXTRA_LABEL;
    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_MINUTES = "pre_minutes";
    public static final String EXTRA_TIME_TEXT = "time_text";

    /** 提前通知的 notification id 段（按 alarmId 区分）。 */
    public static final int NOTIF_ID_BASE = 2000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL_THIS.equals(action)) {
            cancelThisAlarm(context, intent);
        } else if (ACTION_PRE_NOTICE.equals(action)) {
            showPreNotice(context, intent);
        }
    }

    /**
     * 提前提醒：通知栏 + 主动弹窗（v0.31.0）。
     *
     * <p>v0.15.0 起它只是一条静音通知，躺在通知栏里，用户不看就不知道；
     * 现在同时挂一个全屏 intent，锁屏或正在用别的 App 时会直接弹出
     * {@link PreNoticeActivity}，点「知道了」或「关闭这次响铃」即可。
     * 通知本身仍然保留（静音、不震动），弹窗被系统拦下时还有兜底。
     */
    private void showPreNotice(Context ctx, Intent intent) {
        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 0);
        String label = intent.getStringExtra(EXTRA_LABEL);
        int minutes = intent.getIntExtra(EXTRA_MINUTES, Prefs.getPreNoticeMinutes(ctx));
        String timeText = intent.getStringExtra(EXTRA_TIME_TEXT);
        String dateStr = intent.getStringExtra(EXTRA_DATE);
        LocalDate d = parseDateOrToday(dateStr);
        post(ctx, alarmId, label, d, minutes, timeText);
    }

    /** intent extra 里的日期；为空或坏数据时退回今天，别让广播崩掉。 */
    private static LocalDate parseDateOrToday(String s) {
        if (s == null || s.isEmpty()) return LocalDate.now();
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    /**
     * 发一条提前提醒（v0.31.2 抽出，供 {@link ScheduleManager#rescheduleAll} 补发复用）。
     *
     * <p>补发的场景：闹钟是在「提前量窗口内」才设的——比如 14:31 设 15:31 的闹钟、提前 1 小时，
     * 算出来的提醒时刻 14:31 已经过去，注册时会被「pre 已过就别排了」跳过，
     * 这一次就永远收不到提前通知。重排时补一条，行为才和「早点设好」一致。
     */
    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS 可能被用户拒绝；下面的 try-catch 兜底
    static void post(Context ctx, int alarmId, String label, LocalDate d,
                     int minutes, String timeText) {
        if (label == null || label.isEmpty()) label = "闹钟";
        if (timeText == null) timeText = "";
        // 主闹钟仍在登记才提醒：已手动关闭 / 已过期的不打扰
        if (!ScheduleManager.isAlarmRegistered(ctx, alarmId, d)) return;
        // v0.54.0：用「日期 + HH:mm」现算真实剩余分钟。
        // 以前直接把排程时的提前量写进文案：4:23 补发一条、闹钟 4:24 就响，
        // 标题却写「还有 1 小时」——数字完全对不上。
        int real = actualMinutes(d, timeText, minutes);
        if (real <= 0) return;   // 已经到点：交给响铃那条通知，别再补一条过期的预告
        minutes = real;

        Intent popup = new Intent(ctx, PreNoticeActivity.class)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_DATE, d.toString())
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_TIME_TEXT, timeText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent popupPi = PendingIntent.getActivity(ctx, 20 + alarmId, popup,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // v0.31.1：解锁使用中只发横幅通知，不弹整屏——用户正看着屏幕，横幅足够。
        // 息屏 / 锁屏才拉起弹窗，否则通知根本看不见。
        final boolean popupNow = ScreenLock.shouldPopup(ctx);
        if (popupNow) {
            // MIUI / 澎湃 OS 授予「后台弹出界面」时才能成功；被拦下就静默失败，
            // 交给下面的全屏 intent（锁屏）与横幅通知兜底。与 AlarmReceiver 同一套策略。
            try {
                ctx.startActivity(popup);
            } catch (Exception ignore) {
            }
        }

        Intent cancel = new Intent(ctx, PreNoticeReceiver.class)
                .setAction(ACTION_CANCEL_THIS)
                .setData(Uri.parse("daxz://prenotice/" + alarmId + "/" + d))
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_DATE, d.toString());
        PendingIntent cancelPi = PendingIntent.getBroadcast(ctx,
                preNoticeRequestCode(alarmId, d), cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // v0.53.0：正文改成绝对时间。
        //
        // 原来写「1 小时后 · 14:10 响铃」——通知发出去之后不再刷新，这句「1 小时后」
        // 会一直停在原处：等到只剩 10 分钟，它还是「1 小时后」，比不写更误导。
        // 相对时间只在「发出那一刻」成立，而通知要在栏里躺满整个提前量，所以只能写绝对时间。
        //
        // v0.55.3 起「还剩多久」也不再由卡片读秒承担（用户要求去掉），
        // 正文这句绝对时间就是全部信息。横幅上仍有一句相对时间（见下面的 headsUpText）——
        // 它只出现几秒，不存在躺久了失真的问题。
        String sub = whenText(d, timeText);
        Notification.Builder nb = new Notification.Builder(ctx, App.CHANNEL_PRE_NOTICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(label + " 即将响铃")
                .setContentText(sub)
                .setContentIntent(popupPi)
                // v0.53.0：通知的强调色（头部应用名、系统着色）改用品牌紫，
                // 和下面自绘的紫底图标、紫底按钮连成一套。
                .setColor(ContextCompat.getColor(ctx, R.color.indigo))
                // v0.31.2：点进详情页后通知不消失，只有闹钟响过或被关掉才撤（对标系统闹钟）。
                // ongoing 让它无法被划掉——这条通知就是这次响铃的「挂账」。
                .setAutoCancel(false)
                .setOngoing(true)
                // v0.32.1：重排会重发同一条通知来刷新样式，静默更新即可，别再弹一次横幅
                .setOnlyAlertOnce(true)
                // v0.32.0：改成 CATEGORY_ALARM。它本质就是「闹钟要响了」的预告，
                // 用 REMINDER 会被系统当普通提醒折叠；ALARM 类通知在勿扰下也能透出。
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // v0.31.3：action 必须带真实图标。之前传 0，MIUI / HyperOS 认为这条操作
                // 没有图标，干脆不渲染——通知栏里始终看不到「关闭闹钟」按钮。
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_notif_close),
                        "这次不响", cancelPi).build());
        // v0.55.3：这条通知**不再显示读秒倒计时**（用户要求）。
        //
        // 卡片里的 Chronometer 每秒跳一次，眼睛会一直往那儿瞟，反而比「14:10 响铃」
        // 更吵；而且它只精确到秒，对「还有多久」这个判断没有增量信息。
        // 现在正文就是一句绝对时间（whenText），卡片里不留倒计时位。
        //
        // 顺带说明历史：v0.53.0 用 setUsesChronometer 让系统画在通知头部，但设了
        // customContentView 之后头部整行都不再由系统渲染，那几行从来没生效过（v0.53.1 发现）；
        // v0.53.1 改由卡片里的 Chronometer 承担。现在两处都撤掉了。
        //
        // 注意：「稍后再响」那条通知的倒计时**保留**——那里需要一个明确的「还剩多久」，
        // 而且它只活几分钟，不会长时间跳着烦人。
        long triggerAt = triggerAt(d, timeText);
        if (triggerAt > 0) {
            // 只留时间戳（下拉展开后的系统头部会用），不启用读秒
            nb.setWhen(triggerAt).setShowWhen(true);
        }

        // v0.33.0：把按钮画进**折叠态**布局。锁屏卡片和下拉后未展开的卡片渲染的都是折叠态，
        // 而折叠态由系统模板生成，只放小图标 + 标题 + 一行正文，addAction 的按钮要展开才出现——
        // 所以前几版不管怎么补 action、怎么画 heads-up，锁屏上永远看不到「关闭闹钟」。
        // 设了 customContentView，折叠态就是我们的布局，按钮直接躺在卡片上。
        nb.setCustomContentView(
                NotifCard.build(ctx, label + " 即将响铃", sub, 0, popupPi,
                        NotifCard.Button.primary("这次不响", cancelPi)));
        // v0.32.0：横幅（heads-up）也自绘。横幅是另一套视图，不会复用上面的折叠布局。
        // 横幅上的文案可以写相对时间：它只出现几秒，不存在「躺久了失真」的问题，
        // 而「还有 10 分钟」比「14:10」更能让人马上起身。
        nb.setCustomHeadsUpContentView(
                buildHeadsUp(ctx, label + " 即将响铃", headsUpText(minutes, timeText),
                        cancelPi, popupPi));
        // 只有息屏 / 锁屏才挂全屏 intent；解锁时让系统只弹横幅，避免两套界面同时出现
        if (popupNow) {
            nb.setFullScreenIntent(popupPi, true);
        }
        Notification n = nb.build();
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID_BASE + alarmId, n);
            // v0.59.0：提前通知一出现，昨晚那条睡前提醒就该退场——
            // 它说的是「明早 05:00 起床」，而现在这条已经在说「还有 30 分钟」，
            // 两条并排挂在栏里，前者既过时又容易被误读成今天的安排。
            BedtimeReminder.cancelNotice(ctx);
        } catch (Exception ignore) {
        }
    }

    /**
     * 「还有多久」的统一写法：60 分钟写成「1 小时」，别让用户读「60 分钟后」。
     *
     * <p>v0.54.0 补两档：不足 1 分钟写「不到 1 分钟」（原来会写「0 分钟」），
     * 90 分钟写「1 小时 30 分」（原来一律截成「1 小时」）。
     */
    static String minutesText(int minutes) {
        if (minutes < 1) return "不到 1 分钟";
        if (minutes < 60) return minutes + " 分钟";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    /**
     * 真正还剩多少分钟（v0.54.0）。
     *
     * <p>不能信排程时记下的「提前量」：闹钟如果是在提前量窗口内才设的，
     * {@link ScheduleManager#rescheduleAll} 会立刻补发一条，那时候提前量已经过时了。
     * 用户就撞上过这个：4:23 弹出「还有 1 小时」，而闹钟 4:24 就响。
     *
     * <p>这里按「日期 + HH:mm」现算，向上取整（还剩 61 秒算 2 分钟，不写「1 分钟」骗人）。
     *
     * @param fallback 时间文案缺失或格式不对时的兜底值（沿用排程时的提前量）
     * @return 剩余分钟；已到点返回 0
     */
    static int actualMinutes(LocalDate d, String timeText, int fallback) {
        if (d == null || timeText == null || timeText.isEmpty()) return fallback;
        LocalTime t = TimeText.parseClock(timeText);
        if (t == null) return fallback;
        long sec = Duration.between(LocalDateTime.now(), d.atTime(t)).getSeconds();
        if (sec <= 0) return 0;
        return (int) Math.max(1, (sec + 59) / 60);
    }

    /**
     * 折叠卡片上的那行字（v0.53.0）：只有绝对时间，永远正确。
     *
     * <p>「今天」不写，「明天 / 后天」写出来，更远才补日期——一句话说清是哪一趟。
     * 和 {@link PreNoticeActivity} 的弹窗副标题共用同一份措辞。
     */
    static String whenText(LocalDate d, String timeText) {
        if (timeText == null || timeText.isEmpty()) return "即将响铃";
        return dayWord(d) + timeText + " 响铃";
    }

    /**
     * 横幅上的那行字：相对时间 + 绝对时间。
     *
     * <p>横幅只出现几秒，相对时间不会失真；而「还有 10 分钟」比「14:10」更有紧迫感，
     * 两种信息各取所长。锁屏和通知栏用的是折叠卡片，那边只写绝对时间。
     */
    static String headsUpText(int minutes, String timeText) {
        if (timeText == null || timeText.isEmpty()) return minutesText(minutes) + "后响铃";
        return minutesText(minutes) + "后 · " + timeText;
    }

    /** 「今天」不写，「明天 / 后天」写出来，更远才补日期——一句话说清是哪一趟。 */
    static String dayWord(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "";
        if (d.equals(today.plusDays(1))) return "明天 ";
        if (d.equals(today.plusDays(2))) return "后天 ";
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日 ";
    }

    /**
     * 把「日期 + HH:mm」换成时间戳，供通知头部倒计时用。
     *
     * <p>解析不出来返回 0——调用方据此跳过倒计时，退回普通时间戳。
     * 宁可少一个动态效果，也不要显示一个错的剩余时间。
     */
    static long triggerAt(LocalDate d, String timeText) {
        if (d == null || timeText == null || timeText.length() != 5 || timeText.charAt(2) != ':') {
            return 0;
        }
        try {
            int h = Integer.parseInt(timeText.substring(0, 2));
            int m = Integer.parseInt(timeText.substring(3, 5));
            return d.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 拼横幅（heads-up）的自绘布局（v0.32.0）。
     *
     * <p>横幅和折叠卡片是两套独立视图，系统不会拿折叠布局去渲染横幅，所以各画一份。
     */
    private static RemoteViews buildHeadsUp(Context ctx, String title, String text,
                                            PendingIntent cancelPi, PendingIntent contentPi) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.notif_heads_up);
        rv.setTextViewText(R.id.hu_title, title);
        rv.setTextViewText(R.id.hu_text, text);
        theme(ctx, rv, R.id.hu_icon, R.id.hu_title, R.id.hu_text, R.id.hu_action);
        // 药丸按钮只关这一次；图标区域点开详情页，和点正文的行为一致
        rv.setOnClickPendingIntent(R.id.hu_action, cancelPi);
        rv.setOnClickPendingIntent(R.id.hu_icon, contentPi);
        return rv;
    }

    /**
     * 按明暗模式给自绘卡片上色（两套布局共用）。
     *
     * <p>RemoteViews 拿不到当前主题的文字色，只能自己读 uiMode：
     * 浅色卡片用近黑字，深色卡片用白字。
     *
     * <p>v0.53.0 起图标底与按钮底固定为品牌紫渐变（见 {@code bg_notif_icon} /
     * {@code bg_notif_action}），所以这两个位置一律白字 / 白图标，不再按明暗切换背景。
     */
    private static void theme(Context ctx, RemoteViews rv, int iconId, int titleId,
                              int textId, int actionId) {
        rv.setTextColor(titleId, NotifTheme.title(ctx));
        rv.setTextColor(textId, NotifTheme.text(ctx));
        // 紫底上只能放白字、白图标
        rv.setTextColor(actionId, NotifTheme.ON_BRAND);
        rv.setInt(iconId, "setColorFilter", NotifTheme.ON_BRAND);
    }

    /** 提前关闭这次响铃：取消主闹钟 + 提前通知闹钟 + 待触发的小睡。 */
    private void cancelThisAlarm(Context ctx, Intent intent) {
        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 0);
        String dateStr = intent.getStringExtra(EXTRA_DATE);
        LocalDate d = parseDateOrToday(dateStr);
        cancelThis(ctx, alarmId, d);
        try {
            Toast.makeText(ctx, "已关闭这次响铃", Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
    }

    /** 见 {@link #cancelThisAlarm}；弹窗里的「关闭这次响铃」也走这里，逻辑只维护一份。 */
    static void cancelThis(Context ctx, int alarmId, LocalDate d) {
        ScheduleManager.cancelAlarm(ctx, alarmId, d);
        ScheduleManager.cancelPreNotice(ctx, alarmId, d);
        ScheduleManager.cancelSnooze(ctx, alarmId, d);
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_BASE + alarmId);
        } catch (Exception ignore) {
        }
    }

    /** 提前通知的 requestCode：统一由 ScheduleManager 产出，保证注册与取消永远同源。 */
    static int preNoticeRequestCode(int alarmId, LocalDate d) {
        return ScheduleManager.preNoticeRequestCode(alarmId, d);
    }
}
