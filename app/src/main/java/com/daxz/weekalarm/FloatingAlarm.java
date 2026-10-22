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
 * <p>窗口只挂一个实例：重复响铃时先撤旧的再挂新的，不会叠出两张横幅。
 */
final class FloatingAlarm {

    /** 横幅左右各留多少：8dp，和系统通知横幅一致，再宽就顶到屏幕边缘了。 */
    private static final int SIDE_MARGIN_DP = 8;
    /** 距状态栏下沿多远：6dp，贴太紧会像压在状态栏上。 */
    private static final int TOP_GAP_DP = 6;

    private static View view;
    private static WindowManager windowManager;
    /** 只给单测：验「贴顶 + 通栏」这类只能从 LayoutParams 看出来的东西。 */
    private static WindowManager.LayoutParams lastParams;

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
        if (!canFloat(ctx)) return false;
        hide();
        try {
            View v = LayoutInflater.from(ctx).inflate(R.layout.float_alarm, null);

            TextView title = v.findViewById(R.id.fa_title);
            TextView sub = v.findViewById(R.id.fa_sub);
            TextView stop = v.findViewById(R.id.fa_stop);
            TextView snooze = v.findViewById(R.id.fa_snooze);

            String name = label == null || label.isEmpty() ? "闹钟" : label;
            title.setText(name + " 时间到了");
            // v1.42.0：按钮恢复文字（药丸），说明行不用再交代「点哪个按钮」，
            // 直接写「点此停止或小睡」，和通知栏那条卡片同一句话。
            sub.setText(canSnooze ? "点此停止或小睡" : "点此停止");

            stop.setText("停止");
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
                snooze.setText(snoozeMins + " 分钟后再响");
                snooze.setOnClickListener(x -> {
                    hide();
                    try {
                        ctx.startService(new Intent(ctx, AlarmService.class)
                                .setAction(AlarmService.ACTION_SNOOZE)
                                .putExtra(AlarmService.EXTRA_ALARM_ID, alarmId)
                                .putExtra(AlarmService.EXTRA_LABEL, name)
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
                    full.putExtra(AlarmActivity.EXTRA_LABEL, name);
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
            // 贴顶：状态栏下沿再让 6dp。以前是屏幕高度的 18%，看着像浮在半空
            lp.y = statusBarHeight(ctx) + dp(ctx, TOP_GAP_DP);
            lp.windowAnimations = R.style.FloatWindowAnim;

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
