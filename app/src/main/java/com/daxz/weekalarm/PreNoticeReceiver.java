package com.daxz.weekalarm;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;

/**
 * 响铃前的通知栏提醒（v1.17）。
 *
 * 在闹钟响铃前 N 分钟推送一条**安静**的通知（独立低优先级渠道，不响铃不震动），
 * 通知带一个「关闭这次响铃」操作：提前知道闹钟要响，也可以顺手把它关掉。
 *
 * 触发来源：{@link ScheduleManager#rescheduleAll} 为每个已登记的主实例配对注册一条
 * 提前通知闹钟（独立的 PRE_NOTICE requestCode 段，见该类注释）。
 */
public class PreNoticeReceiver extends BroadcastReceiver {

    public static final String ACTION_PRE_NOTICE = "com.daxz.weekalarm.PRE_NOTICE";
    public static final String ACTION_CANCEL_THIS = "com.daxz.weekalarm.CANCEL_THIS";
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
     * 提前提醒：通知栏 + 主动弹窗（v1.23）。
     *
     * <p>v1.17 起它只是一条静音通知，躺在通知栏里，用户不看就不知道；
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
        LocalDate d;
        if (dateStr != null) {
            d = LocalDate.parse(dateStr);
        } else {
            d = LocalDate.now();
        }
        post(ctx, alarmId, label, d, minutes, timeText);
    }

    /**
     * 发一条提前提醒（v1.23.2 抽出，供 {@link ScheduleManager#rescheduleAll} 补发复用）。
     *
     * <p>补发的场景：闹钟是在「提前量窗口内」才设的——比如 14:31 设 15:31 的闹钟、提前 1 小时，
     * 算出来的提醒时刻 14:31 已经过去，注册时会被「pre 已过就别排了」跳过，
     * 这一次就永远收不到提前通知。重排时补一条，行为才和「早点设好」一致。
     */
    static void post(Context ctx, int alarmId, String label, LocalDate d,
                     int minutes, String timeText) {
        if (label == null || label.isEmpty()) label = "闹钟";
        if (timeText == null) timeText = "";
        // 主闹钟仍在登记才提醒：已手动关闭 / 已过期的不打扰
        if (!ScheduleManager.isAlarmRegistered(ctx, alarmId, d)) return;

        Intent popup = new Intent(ctx, PreNoticeActivity.class)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_DATE, d.toString())
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_TIME_TEXT, timeText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent popupPi = PendingIntent.getActivity(ctx, 20 + alarmId, popup,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // v1.23.1：解锁使用中只发横幅通知，不弹整屏——用户正看着屏幕，横幅足够。
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

        // v1.23.6：正文不再写「点此查看」，改为「还有多久 · 几点响」——
        // 折叠卡片上的那行字只有一行的位置，说清楚时间比说「点这里」有用。
        String sub = minutesText(minutes) + "后"
                + (timeText.isEmpty() ? "" : " · " + timeText) + " 响铃";
        Notification.Builder nb = new Notification.Builder(ctx, App.CHANNEL_PRE_NOTICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(label + " 即将响铃")
                .setContentText(sub)
                .setContentIntent(popupPi)
                // v1.23.2：点进详情页后通知不消失，只有闹钟响过或被关掉才撤（对标系统闹钟）。
                // ongoing 让它无法被划掉——这条通知就是这次响铃的「挂账」。
                .setAutoCancel(false)
                .setOngoing(true)
                // v1.23.5：重排会重发同一条通知来刷新样式，静默更新即可，别再弹一次横幅
                .setOnlyAlertOnce(true)
                // v1.23.4：改成 CATEGORY_ALARM。它本质就是「闹钟要响了」的预告，
                // 用 REMINDER 会被系统当普通提醒折叠；ALARM 类通知在勿扰下也能透出。
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // v1.23.3：action 必须带真实图标。之前传 0，MIUI / HyperOS 认为这条操作
                // 没有图标，干脆不渲染——通知栏里始终看不到「关闭闹钟」按钮。
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_notif_close),
                        "关闭闹钟", cancelPi).build());
        // v1.23.6：把按钮画进**折叠态**布局。锁屏卡片和下拉后未展开的卡片渲染的都是折叠态，
        // 而折叠态由系统模板生成，只放小图标 + 标题 + 一行正文，addAction 的按钮要展开才出现——
        // 所以前几版不管怎么补 action、怎么画 heads-up，锁屏上永远看不到「关闭闹钟」。
        // 设了 customContentView，折叠态就是我们的布局，按钮直接躺在卡片上。
        nb.setCustomContentView(
                buildCard(ctx, label + " 即将响铃", sub, cancelPi, popupPi));
        // v1.23.4：横幅（heads-up）也自绘。横幅是另一套视图，不会复用上面的折叠布局。
        nb.setCustomHeadsUpContentView(
                buildHeadsUp(ctx, label + " 即将响铃", minutesText(minutes) + "后响铃",
                        cancelPi, popupPi));
        // 只有息屏 / 锁屏才挂全屏 intent；解锁时让系统只弹横幅，避免两套界面同时出现
        if (popupNow) {
            nb.setFullScreenIntent(popupPi, true);
        }
        Notification n = nb.build();
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID_BASE + alarmId, n);
        } catch (Exception ignore) {
        }
    }

    /** 「还有多久」的统一写法：60 分钟写成「1 小时」，别让用户读「60 分钟后」。 */
    static String minutesText(int minutes) {
        return minutes >= 60 ? (minutes / 60) + " 小时" : minutes + " 分钟";
    }

    /**
     * 拼横幅（heads-up）的自绘布局（v1.23.4）。
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
     * 拼折叠卡片的自绘布局（v1.23.6）。
     *
     * <p>锁屏和「下拉但没展开」都是这一态。按钮放在这里，才不用先展开才能关。
     */
    private static RemoteViews buildCard(Context ctx, String title, String text,
                                         PendingIntent cancelPi, PendingIntent contentPi) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.notif_content);
        rv.setTextViewText(R.id.nc_title, title);
        rv.setTextViewText(R.id.nc_text, text);
        theme(ctx, rv, R.id.nc_icon, R.id.nc_title, R.id.nc_text, R.id.nc_action);
        rv.setOnClickPendingIntent(R.id.nc_action, cancelPi);
        rv.setOnClickPendingIntent(R.id.nc_icon, contentPi);
        return rv;
    }

    /**
     * 按明暗模式给自绘卡片上色（两套布局共用）。
     *
     * <p>RemoteViews 拿不到当前主题的文字色，只能自己读 uiMode：
     * 浅色卡片用近黑字，深色卡片用白字，按钮底和图标底也各配一份 drawable。
     * 图标本身是白色描边，画在浅灰圆底上等于看不见，所以还要一层 colorFilter。
     */
    private static void theme(Context ctx, RemoteViews rv, int iconId, int titleId,
                              int textId, int actionId) {
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int titleColor = night ? 0xFFFFFFFF : 0xFF1A1A1A;
        int subColor = night ? 0xB3FFFFFF : 0xFF6E6E73;
        rv.setTextColor(titleId, titleColor);
        rv.setTextColor(textId, subColor);
        rv.setTextColor(actionId, titleColor);
        rv.setInt(iconId, "setColorFilter", titleColor);
        rv.setInt(actionId, "setBackgroundResource",
                night ? R.drawable.bg_notif_action_dark : R.drawable.bg_notif_action);
        rv.setInt(iconId, "setBackgroundResource",
                night ? R.drawable.bg_notif_icon_dark : R.drawable.bg_notif_icon);
    }

    /** 提前关闭这次响铃：取消主闹钟 + 提前通知闹钟 + 待触发的小睡。 */
    private void cancelThisAlarm(Context ctx, Intent intent) {
        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 0);
        String dateStr = intent.getStringExtra(EXTRA_DATE);
        LocalDate d = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();
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
