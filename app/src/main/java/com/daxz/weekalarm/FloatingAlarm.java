package com.daxz.weekalarm;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 响铃时的贴顶横幅（v1.40.0 起是居中小卡片，v1.41.0 改成系统闹钟那种横幅）。
 *
 * <p>用户原话：「解锁使用中响铃，弹一个小悬浮窗，不要整屏」——v1.40.0 做了个 300dp 的卡片，
 * 结果它浮在屏幕 18% 处，左右留白、上下悬空，看着像个不知道从哪冒出来的弹窗。
 * 系统闹钟的做法是<b>贴顶通栏</b>：从状态栏下面滑下来、左右只留 8dp、右边两个圆钮，
 * 既显眼又不挡屏幕中间。这一版照那个做。
 *
 * <p>为什么不用 Activity：Activity 就算做成对话框样式，窗口仍然占满整屏、会盖住状态栏，
 * 还得处理生命周期；{@code TYPE_APPLICATION_OVERLAY} 只占它自己那一块。
 * 代价是要「显示悬浮窗」权限，没权限时 {@link #show} 返回 false，
 * 调用方（{@link AlarmService}）退回整屏，不会静默什么都不弹。
 *
 * <p>v1.42.0：结构改成系统 heads-up 那种三行（图标 + 标题 / 一行说明 / 一排药丸），
 * 底色换暖桃、按钮换品牌紫。同时响铃通知改走低优先级渠道——否则高优先级渠道
 * 会在屏幕亮着时再弹一张系统 heads-up，两层叠在一起（用户报的「两个悬浮窗」）。
 *
 * <p>v1.43.0：用户要「透明感 + 位置更贴顶 + 布局照系统闹钟」。透明做不到——
 * {@code FLAG_BLUR_BEHIND} 与 {@code setBlurBehindRadius} 需要 Android 12+
 * <b>且系统开启跨窗口模糊</b>（{@code isCrossWindowBlurEnabled()}），WMS 还会做权限检查，
 * 第三方悬浮窗基本被静默忽略。所以退一步用「深色 + 88% 不透明」近似通知栏的观感；
 * 模糊只在系统真的支持时才挂，不支持就纯半透明，不做无效尝试。
 * 布局改成系统 heads-up 的单行：大图标 + 大号时刻 + 闹钟名 + 两个圆钮。
 *
 * <p>窗口只挂一个实例：重复响铃时先撤旧的再挂新的，不会叠出两张横幅。
 */
final class FloatingAlarm {

    /** 横幅左右各留多少：8dp，和系统通知横幅一致，再宽就顶到屏幕边缘了。 */
    private static final int SIDE_MARGIN_DP = 8;
    /**
     * 距状态栏下沿多远：2dp。
     *
     * <p>v1.42.0 是 6dp，用户仍说「位置太靠下」。留 2dp 而不是 0：完全贴死时
     * 圆角会和状态栏图标咬在一起，视觉上像被截断；2dp 已经是「贴顶」的观感。
     */
    private static final int TOP_GAP_DP = 2;

    /** 系统支持跨窗口模糊时的模糊半径。20dp 接近通知栏的磨砂程度。 */
    private static final int BLUR_RADIUS_DP = 20;

    private static View view;
    private static WindowManager windowManager;
    /** 只给单测：验「贴顶 + 通栏」这类只能从 LayoutParams 看出来的东西。 */
    private static WindowManager.LayoutParams lastParams;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private FloatingAlarm() {
    }

    /** 能不能挂悬浮窗：Android 8.0+ 的 TYPE_APPLICATION_OVERLAY 需要用户授权。 */
    static boolean canFloat(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isShowing() {
        return view != null;
    }

    /** 只给单测：拿到当前挂着的那个 View，验文案与按钮。 */
    static View viewForTest() {
        return view;
    }

    /** 只给单测：拿到挂上去时用的窗口参数，验位置与宽度。 */
    static WindowManager.LayoutParams paramsForTest() {
        return lastParams;
    }

    /**
     * 挂上横幅。返回 false 表示挂不上去（没权限 / 系统拒绝），调用方应退回整屏。
     *
     * @param dateStr 响铃日期，小睡按钮要用它算次数落在哪一天
     */
    static boolean show(Context ctx, int alarmId, String label, String dateStr,
                        int snoozeMins, boolean canSnooze) {
        return show(ctx, alarmId, label, dateStr, null, snoozeMins, canSnooze);
    }

    /**
     * 挂上横幅（带响铃时刻）。
     *
     * @param timeText 响铃时刻「HH:mm」。为 null 时退回当前时间——
     *                 横幅是「正在响」的那一刻弹的，两者通常只差几秒
     */
    static boolean show(Context ctx, int alarmId, String label, String dateStr,
                        String timeText, int snoozeMins, boolean canSnooze) {
        if (!canFloat(ctx)) return false;
        hide();
        try {
            View v = LayoutInflater.from(ctx).inflate(R.layout.float_alarm, null);

            TextView time = v.findViewById(R.id.fa_time);
            TextView name = v.findViewById(R.id.fa_name);
            View stop = v.findViewById(R.id.fa_stop);
            View snooze = v.findViewById(R.id.fa_snooze);

            // 大号时刻是这张卡的主信息：用户被叫醒第一眼确认的是「几点」，
            // 闹钟名只用来区分「这是哪一个」
            time.setText(timeText == null || timeText.isEmpty()
                    ? LocalTime.now().format(TIME_FMT) : timeText);
            name.setText(label == null || label.isEmpty() ? "闹钟" : label);

            // 圆钮没有文字，无障碍描述与长按提示都得自己给，否则读屏只会念「按钮」
            stop.setContentDescription("停止");
            stop.setTooltipText("停止");
            snooze.setContentDescription("稍后提醒 " + snoozeMins + " 分钟");
            snooze.setTooltipText("稍后提醒 " + snoozeMins + " 分钟");

            stop.setOnClickListener(x -> {
                hide();
                try {
                    ctx.startService(new Intent(ctx, AlarmService.class)
                            .setAction(AlarmService.ACTION_STOP));
                } catch (Exception ignore) {
                }
            });

            if (canSnooze) {
                snooze.setVisibility(View.VISIBLE);
                snooze.setOnClickListener(x -> {
                    hide();
                    try {
                        ctx.startService(new Intent(ctx, AlarmService.class)
                                .setAction(AlarmService.ACTION_SNOOZE)
                                .putExtra(AlarmService.EXTRA_ALARM_ID, alarmId)
                                .putExtra(AlarmService.EXTRA_LABEL, label)
                                .putExtra(AlarmService.EXTRA_DATE, dateStr));
                    } catch (Exception ignore) {
                    }
                });
            } else {
                snooze.setVisibility(View.GONE);
            }

            // 点卡片空白处 = 看详情（整屏页），和横幅上的按钮不冲突
            v.setOnClickListener(x -> {
                try {
                    Intent full = new Intent(ctx, AlarmActivity.class);
                    full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
                    full.putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId);
                    full.putExtra(AlarmActivity.EXTRA_LABEL, label);
                    full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(full);
                } catch (Exception ignore) {
                }
            });

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int side = dp(ctx, SIDE_MARGIN_DP);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    // 通栏：屏幕宽减去左右各 8dp。写 WRAP_CONTENT 就退回成 300dp 的小卡片
                    Math.max(dp(ctx, 280), dm.widthPixels - side * 2),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // 不抢焦点：来电话、打字、返回键都照常走下面的应用
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            // 贴顶：状态栏下沿再让 2dp。以前是屏幕高度的 18%，看着像浮在半空
            lp.y = statusBarHeight(ctx) + dp(ctx, TOP_GAP_DP);
            lp.windowAnimations = R.style.FloatWindowAnim;
            applyBlurIfSupported(ctx, lp);

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            wm.addView(v, lp);
            windowManager = wm;
            view = v;
            lastParams = lp;
            return true;
        } catch (Exception e) {
            // BadTokenException / 权限被回收：清理干净，让调用方退回整屏
            view = null;
            windowManager = null;
            lastParams = null;
            return false;
        }
    }

    /**
     * 撤掉横幅。没挂过、或已经被系统回收时静默返回。
     *
     * <p>用 {@code removeView} 而不是 {@code removeViewImmediate}：前者会让
     * {@code FloatWindowAnim} 里的退场动画跑完，后者是「啪」地消失。
     */
    static void hide() {
        if (view == null || windowManager == null) {
            view = null;
            windowManager = null;
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Exception ignore) {
        }
        view = null;
        windowManager = null;
        lastParams = null;
    }

    /**
     * 系统支持跨窗口模糊时才挂模糊，否则什么也不做。
     *
     * <p>为什么必须判断：{@code setBlurBehindRadius} 在 Android 12 以下不存在（直接崩），
     * 12 以上如果系统没开「跨窗口模糊」或厂商没实现，会被 WMS 静默忽略——
     * 不判断的话这段代码在多数国产 ROM 上等于白写，还会让人误以为「已经加了磨砂」。
     * 判断为假时卡片仍然是半透明，只是没有磨砂，观感差别不大。
     */
    private static void applyBlurIfSupported(Context ctx, WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null || !wm.isCrossWindowBlurEnabled()) return;
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(dp(ctx, BLUR_RADIUS_DP));
        } catch (Throwable ignore) {
            // 个别 ROM 的这个方法会抛，模糊只是锦上添花，失败就保持纯半透明
        }
    }

    /** 状态栏高度。取不到（部分 ROM 不暴露这个 dimen）就按 24dp 估。 */
    private static int statusBarHeight(Context c) {
        Resources r = c.getResources();
        int id = r.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = r.getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(c, 24);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
