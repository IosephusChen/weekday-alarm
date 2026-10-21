package com.daxz.weekalarm;

import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

/**
 * 通知卡片（折叠态）的统一画法（v1.38.0）。
 *
 * <p>之前三条通知各画各的：提前提醒有自绘卡片，响铃和睡前提醒还在用系统模板，
 * 于是同一款 App 的通知长得不像一家——用户看到的直接后果是
 * 「响铃通知的按钮是透明的」（系统模板的 action 就是灰色半透明药丸）、
 * 「睡前提醒说有『明天请假』按钮，实际没有」（addAction 只在展开后出现）。
 *
 * <p>现在统一走 {@link R.layout#notif_content}：紫渐变图标 + 标题 + 正文
 * + 可选的倒计时 + 至多两个药丸按钮（主操作实色、次操作描边）。
 *
 * <p><b>倒计时为什么画在卡片里</b>：原来用 {@code setUsesChronometer} 让系统画在通知头部，
 * 但一旦设了 customContentView，头部整行都不再由系统渲染，那个倒计时从来没出现过。
 * RemoteViews 支持 {@link android.widget.Chronometer}，放进布局里由 SystemUI 每秒自己走，
 * 同样不占我们的 CPU。
 */
final class NotifCard {

    private NotifCard() {
    }

    /** 一个药丸按钮：主操作实色紫渐变，次操作紫描边。 */
    static final class Button {
        final String text;
        final PendingIntent pi;
        final boolean primary;

        private Button(String text, PendingIntent pi, boolean primary) {
            this.text = text;
            this.pi = pi;
            this.primary = primary;
        }

        /** 主操作：实色紫底白字。一条通知里最多一个。 */
        static Button primary(String text, PendingIntent pi) {
            return new Button(text, pi, true);
        }

        /** 次操作：紫描边 + 紫字。 */
        static Button secondary(String text, PendingIntent pi) {
            return new Button(text, pi, false);
        }
    }

    /**
     * 画一张卡片。
     *
     * @param countdownAt 倒计时终点（wall clock 毫秒）。≤0 表示不显示倒计时——
     *                    解析不出准确时间时宁可不显示，也不要倒一个错的数
     * @param contentPi   点图标 / 卡片正文的去向，可为 null
     * @param buttons     至多两个；多给的忽略。超过两个按钮在窄屏上会挤成一团
     */
    static RemoteViews build(Context ctx, String title, String text,
                             long countdownAt, PendingIntent contentPi, Button... buttons) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.notif_content);
        rv.setTextViewText(R.id.nc_title, title);
        rv.setTextViewText(R.id.nc_text, text);

        boolean night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // RemoteViews 拿不到当前主题的文字色，只能自己读 uiMode
        rv.setTextColor(R.id.nc_title, night ? 0xFFFFFFFF : 0xFF1E2235);
        rv.setTextColor(R.id.nc_text, night ? 0xB3FFFFFF : 0xFF6B7189);
        // 紫底上只能放白图标
        rv.setInt(R.id.nc_icon, "setColorFilter", 0xFFFFFFFF);
        if (contentPi != null) {
            rv.setOnClickPendingIntent(R.id.nc_icon, contentPi);
        }

        if (countdownAt > 0) {
            // 注意基准：RemoteViews.setChronometer 的 base 是 elapsedRealtime 时基，
            // 不是 System.currentTimeMillis。直接传 wall clock 会显示成几十万小时。
            long base = SystemClock.elapsedRealtime()
                    + Math.max(0L, countdownAt - System.currentTimeMillis());
            rv.setViewVisibility(R.id.nc_chrono, View.VISIBLE);
            rv.setChronometer(R.id.nc_chrono, base, null, true);
            rv.setChronometerCountDown(R.id.nc_chrono, true);
            rv.setTextColor(R.id.nc_chrono, night ? 0xFFC4B5FD : ContextCompat.getColor(ctx, R.color.indigo));
        } else {
            rv.setViewVisibility(R.id.nc_chrono, View.GONE);
        }

        Button primary = null;
        Button secondary = null;
        for (Button b : buttons) {
            if (b == null) continue;
            if (b.primary && primary == null) {
                primary = b;
            } else if (secondary == null) {
                secondary = b;
            }
        }
        if (primary != null) {
            rv.setTextViewText(R.id.nc_action, primary.text);
            rv.setTextColor(R.id.nc_action, 0xFFFFFFFF);
            rv.setViewVisibility(R.id.nc_action, View.VISIBLE);
            rv.setOnClickPendingIntent(R.id.nc_action, primary.pi);
        } else {
            rv.setViewVisibility(R.id.nc_action, View.GONE);
        }
        if (secondary != null) {
            rv.setTextViewText(R.id.nc_action2, secondary.text);
            rv.setTextColor(R.id.nc_action2,
                    night ? 0xFFC4B5FD : ContextCompat.getColor(ctx, R.color.indigo));
            rv.setViewVisibility(R.id.nc_action2, View.VISIBLE);
            rv.setOnClickPendingIntent(R.id.nc_action2, secondary.pi);
        } else {
            rv.setViewVisibility(R.id.nc_action2, View.GONE);
        }
        return rv;
    }
}
