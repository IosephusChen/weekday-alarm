package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.core.content.ContextCompat;

/**
 * 通知卡片（RemoteViews）的配色（v0.55.0）。
 *
 * <p><b>为什么单独一个类</b>：{@link NotifCard} 与 {@code PreNoticeReceiver} 各自画一套
 * RemoteViews，两边都要「按明暗模式给文字上色」，于是同一个 {@code uiMode} 判断
 * 和同一批魔法值（{@code 0xFF1E2235} / {@code 0xB3FFFFFF} / {@code 0xFFC4B5FD}）
 * 被抄了两遍。改一处忘一处，就是「提前提醒看得清、响铃通知看不清」这类事故的来源。
 *
 * <p><b>为什么不能直接用 {@code @color/ink}</b>：RemoteViews 里的文字色由 SystemUI
 * 的进程渲染，拿不到我们的主题属性，只能喂绝对 ARGB。所以这里读 {@code uiMode}
 * 自己挑一套，和资源里的 {@code values-night} 是两条独立的路——改颜色时两边都要动。
 *
 * <p>注意「明暗」在这里指<b>通知栏的明暗</b>，不是 App 界面的明暗。两者通常一致，
 * 但用户可以单独把本 App 设成浅色（HyperOS「深色模式 → 单独的应用」），这时 App 的
 * {@code uiMode} 是日间，而锁屏卡片仍是深色——按 App 的配置挑色就是黑字压黑底，
 * 标题直接消失（v0.87.5 用户截图）。所以这里只问<b>系统</b>，不问 App。
 */
final class NotifTheme {

    private NotifTheme() {
    }

    /**
     * 通知栏是否为深色。
     *
     * <p>v0.87.7：数据源从 {@code ctx.getResources()} 换成 {@link Resources#getSystem()}。
     * 通知卡片的底色是 SystemUI 画的，跟系统全局深浅走；App 可以被单独设成浅色，
     * 那时两个 {@code uiMode} 就不一致了，必须以系统为准。
     */
    static boolean isNight() {
        return (Resources.getSystem().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 标题：浅色栏近黑，深色栏纯白。 */
    static int title() {
        return isNight() ? 0xFFFFFFFF : 0xFF1E2235;
    }

    /** 正文 / 副标题：浅色栏中灰，深色栏 70% 白。 */
    static int text() {
        return isNight() ? 0xB3FFFFFF : 0xFF6B7189;
    }

    /**
     * 品牌紫底上的文字与图标：两套模式都是纯白。
     *
     * <p>v0.53.0 起图标底与主按钮底固定为品牌紫渐变（{@code bg_notif_icon} /
     * {@code bg_notif_action}），不随明暗切换，所以压在上面的东西不需要分模式。
     */
    static final int ON_BRAND = 0xFFFFFFFF;

    /**
     * 强调色（倒计时、次要按钮文字）：浅色栏用 indigo，深色栏用 violet-300。
     *
     * <p>深色不用 {@code @color/indigo} 的夜间值（#818CF8）——那是给 App 界面的卡片底算的，
     * 在通知栏的纯黑底上偏暗，实测不如 #C4B5FD 醒目。
     */
    static int accent(Context ctx) {
        return isNight() ? 0xFFC4B5FD : ContextCompat.getColor(ctx, R.color.indigo);
    }
}
