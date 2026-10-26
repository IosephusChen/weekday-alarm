package com.daxz.weekalarm;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.time.LocalTime;

/**
 * 响铃时的贴顶横幅（v0.54.0 起是居中小卡片，v0.54.1 改成系统闹钟那种横幅）。
 *
 * <p>用户原话：「解锁使用中响铃，弹一个小悬浮窗，不要整屏」——v0.54.0 做了个 300dp 的卡片，
 * 结果它浮在屏幕 18% 处，左右留白、上下悬空，看着像个不知道从哪冒出来的弹窗。
 * 系统闹钟的做法是<b>贴顶通栏</b>：从状态栏下面滑下来、左右只留 8dp、右边两个圆钮，
 * 既显眼又不挡屏幕中间。这一版照那个做。
 *
 * <p>为什么不用 Activity：Activity 就算做成对话框样式，窗口仍然占满整屏、会盖住状态栏，
 * 还得处理生命周期；{@code TYPE_APPLICATION_OVERLAY} 只占它自己那一块。
 * 代价是要「显示悬浮窗」权限，没权限时 {@link #show} 返回 false，
 * 调用方（{@link AlarmService}）退回整屏，不会静默什么都不弹。
 *
 * <p>v0.54.2：结构改成系统 heads-up 那种三行（图标 + 标题 / 一行说明 / 一排药丸），
 * 底色换暖桃、按钮换品牌紫。同时响铃通知改走低优先级渠道——否则高优先级渠道
 * 会在屏幕亮着时再弹一张系统 heads-up，两层叠在一起（用户报的「两个悬浮窗」）。
 *
 * <p>v0.55.0：用户要「透明感 + 位置更贴顶 + 布局照系统闹钟」。布局改成系统 heads-up
 * 的单行（大图标 + 大号时刻 + 闹钟名 + 两个圆钮），贴顶距离 6dp → 2dp。
 * 透明感用「深色 + 88% 不透明」近似——卡片本身带 alpha，底下的内容能透出轮廓。
 *
 * <p><b>模糊已经试过，不要再加。</b>v0.55.0 曾按 {@code isCrossWindowBlurEnabled()}
 * 挂 {@code FLAG_BLUR_BEHIND} + {@code setBlurBehindRadius}，在小米 HyperOS 上
 * 实际效果是<b>整屏磨砂</b>——不只是横幅背后那块，屏幕上所有内容都糊成一片，
 * 用户报「看不见屏幕上的其他内容了」。这正好把悬浮横幅的初衷（不挡住下面的东西）
 * 做反了。{@code FLAG_BLUR_BEHIND} 的语义是「模糊窗口背后的背景」，但模糊区域
 * 由 WMS 按窗口算，各家 ROM 实现不一致，第三方悬浮窗拿不到「只糊自己那一块」的保证。
 * 所以这里彻底不碰模糊标志，只靠 alpha 做透明感。
 *
 * <p>窗口只挂一个实例：重复响铃时先撤旧的再挂新的，不会叠出两张横幅。
 */
final class FloatingAlarm {

    /** 横幅左右各留多少：8dp，和系统通知横幅一致，再宽就顶到屏幕边缘了。 */
    static final int SIDE_MARGIN_DP = 8;
    /**
     * 窗口顶边与状态栏下沿的距离：0dp，即紧贴状态栏。
     *
     * <p>位置演进：v0.54.1 是屏幕高度的 18%（浮在半空）→ v0.54.2「状态栏下沿 + 6dp」
     * → v0.55.0「+2dp」，用户仍说「太靠下」→ v0.55.2 写成「状态栏高度 × 0.5」
     * 并加 {@code FLAG_LAYOUT_IN_SCREEN}，想压进状态栏。
     *
     * <p><b>v0.55.2 那个 0.5 其实没生效。</b>用户截图实测：横幅顶边 y = 142px，
     * 密度 3.05，换算 46.5dp，正好等于状态栏高度——也就是说系统（WMS）仍把 overlay
     * 窗口的 y clamp 回了状态栏下沿，乘 0.5 等于白写。效果碰巧是对的（状态栏完整可见，
     * OCR 能读出「12:09 / 信号 / 电量」），但那是靠系统兜底，换一台不 clamp 的 ROM
     * 就会真把横幅塞进状态栏里盖住时间。
     *
     * <p>v0.55.3 据此把 {@code FLAG_LAYOUT_IN_SCREEN} 也撤了，只留「状态栏高度」当 y。
     * <b>这是个回归</b>：不带该标志时窗口原点已在状态栏下方，y 又加了一次状态栏高度，
     * 实际落点变成「2 × 状态栏高度」（约 92dp），真机上就是「横幅往下掉了一截」。
     *
     * <p>v0.58.3 改回「带标志 + y = 状态栏高度」：标志只把原点挪回屏幕顶，
     * 落点仍是紧贴状态栏下沿，一个像素都不重叠。两条路（系统 clamp / 不 clamp）都指向同一位置。
     *
     * <p><b>v0.73.7 又改了两处，都是用户横屏截图反馈</b>：① 宽度从前是
     * {@code 屏宽 - 16dp}，横屏下短边不变、长边翻倍，横幅被拉成竖屏的两倍长
     * （「太长，没必要，做成竖屏那样的长度就行」）——现在按<b>屏幕短边</b>算，
     * 两个朝向等长，旋屏时宽度根本不变；② y 从前固定「状态栏高度」，
     * 横屏看视频时状态栏是隐藏的，那 160px 黑边死区让横幅看起来漂在半空
     * （「太靠下了，要靠到最上方」）——现在 y 由<b>可见系统栏 inset</b> 驱动，
     * 状态栏隐藏时 inset 为 0，横幅贴屏幕最顶。同时注册配置变化回调
     * （{@link #relayout()}）：旋屏不再重建窗口，不重算就会带着旧朝向的尺寸，
     * 横屏响铃直接转竖屏会把右侧裁掉一截。
     */
    private static final int TOP_GAP_DP = 0;

    /** 短提示停留多久：够读完「闹钟 5 分钟后再响」一行，又不至于挡着人操作。 */
    private static final long TOAST_MS = 2400;

    /**
     * 短提示距屏幕底部多远：110dp。
     *
     * <p>放底部不放中间：横幅刚从上边消失，回执再出现在中间会显得「又弹了一张卡」；
     * 110dp 大致是单手拇指的自然落点，也避开导航条。
     */
    private static final int TOAST_BOTTOM_DP = 110;

    private static View view;
    private static WindowManager windowManager;
    /** 只给单测：验「贴顶 + 通栏」这类只能从 LayoutParams 看出来的东西。 */
    private static WindowManager.LayoutParams lastParams;
    /**
     * 最近一次从窗口 inset 读到的「可见系统栏」顶高，null = 还没收到过。
     *
     * <p>旋屏时 inset 会重新派发，但重算窗口参数的那一刻可能还没到，
     * 所以先把最近的值存着，配置变化时先按它落位，等新 inset 到了再校正。
     */
    private static Integer lastInsetTop;
    /** 配置变化（旋屏 / 字体缩放 / 分屏）时重算窗口几何。见 {@link #relayout()}。 */
    private static final ComponentCallbacks2 CONFIG_CALLBACKS = new ComponentCallbacks2() {
        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            relayout();
        }

        @Override
        public void onTrimMemory(int level) {
        }

        @Override
        public void onLowMemory() {
        }
    };
    /** registerComponentCallbacks 用的 application context，hide 时原样注销。 */
    private static Context appCtx;


    // 短提示用自己的一组字段，不和横幅共用——点「稍后提醒」时横幅正在退场，
    // 共用字段会让 hide() 把刚挂上的提示一起清掉
    private static View toastView;
    private static WindowManager toastWm;
    private static WindowManager.LayoutParams toastParams;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable toastTimeout;

    private FloatingAlarm() {
    }

    /** 能不能挂悬浮窗：Android 8.0+ 的 TYPE_APPLICATION_OVERLAY 需要用户授权。 */
    static boolean canFloat(Context c) {
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
                    ? TimeText.clock(LocalTime.now()) : timeText);
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

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    // 宽度占位，真正的值由 applyGeometry 按屏幕短边算：
                    // 横屏时短边就是竖屏屏宽，横幅长度两个朝向一致，
                    // 不会拉成通栏（v0.73.7 前直接用 widthPixels，横屏横幅是竖屏的两倍长，
                    // 用户原话「太长，没必要，做成竖屏那样的长度就行」）
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // 不抢焦点：来电话、打字、返回键都照常走下面的应用。
                    // v0.58.3：必须带 FLAG_LAYOUT_IN_SCREEN，否则坐标系不是屏幕顶——
                    // 不带它时窗口原点已经在状态栏下方，再减 statusBarHeight 就多让了一整个状态栏
                    // （46dp 左右），真机上表现为「横幅无缘无故往下掉了一截」。
                    // 这个标志只影响原点，y 仍是状态栏高度，所以不会盖住时间和电量。
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            applyGeometry(ctx, lp);
            lp.windowAnimations = R.style.FloatWindowAnim;
            // 不加 FLAG_BLUR_BEHIND：在 HyperOS 上会糊掉整屏，见类注释

            // v0.73.7：y 改由「可见系统栏」inset 驱动。状态栏被隐藏时（横屏看视频、
            // 游戏全屏）inset 为 0，横幅贴到屏幕最顶——那 160px 黑边是死区，
            // 排在过去用户看到的就是「漂在半空」。状态栏可见时 inset 就是它的高度，
            // 行为和从前「紧贴状态栏下沿」一致。
            v.setOnApplyWindowInsetsListener((vv, insets) -> {
                int top = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? insets.getInsets(WindowInsets.Type.systemBars()).top
                        : insets.getSystemWindowInsetTop();
                applyTopInset(vv, top);
                // 不消费：卡片里的时刻、按钮还要照常排版
                return insets;
            });

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            wm.addView(v, lp);
            windowManager = wm;
            view = v;
            lastParams = lp;
            appCtx = ctx.getApplicationContext();
            if (appCtx != null) {
                // 旋屏时横幅窗口不会自动重算尺寸：v0.73.7 前横屏响铃直接转竖屏，
                // 窗口宽度还是横屏的像素值，比竖屏屏宽还大，右侧被裁掉一截。
                // 注册后每次配置变化都重算，旋转、分屏、字体缩放都覆盖。
                appCtx.registerComponentCallbacks(CONFIG_CALLBACKS);
            }
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
        if (appCtx != null) {
            try {
                appCtx.unregisterComponentCallbacks(CONFIG_CALLBACKS);
            } catch (Exception ignore) {
            }
            appCtx = null;
        }
        lastInsetTop = null;
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
     * 按当前屏幕几何重算横幅的宽和高位（v0.73.7）。
     *
     * <p><b>宽度按短边算</b>：{@code min(宽, 高) - 左右边距}。竖屏时短边就是屏宽，
     * 和从前「通栏」一致；横屏时短边仍是竖屏屏宽，所以横幅长度两个朝向相同——
     * 这正是用户要的「做成竖屏那样的长度」。副作用是旋屏时宽度根本不变，
     * 「横屏响铃转竖屏后超出画面」从此不可能发生。
     *
     * <p><b>y 用最近一次可见系统栏 inset</b>（{@link #lastInsetTop}）：没收到过
     * inset 时退回状态栏高度。为什么不直接读 inset：inset 是窗口挂上之后才派发的，
     * addView 的那一刻只能先用状态栏高度占位。
     */
    private static void applyGeometry(Context ctx, WindowManager.LayoutParams lp) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int side = dp(ctx, SIDE_MARGIN_DP);
        // 280dp 下限：小窗 / 分屏时短边可能很窄，别把横幅压成一条线
        lp.width = Math.max(dp(ctx, 280),
                Math.min(dm.widthPixels, dm.heightPixels) - side * 2);
        int top = lastInsetTop != null ? lastInsetTop : statusBarHeight(ctx);
        lp.y = top + dp(ctx, TOP_GAP_DP);
    }

    /**
     * 收到窗口 inset 后校正 y（v0.73.7）。
     *
     * <p>只看<b>可见</b>的系统栏（{@code getInsets(systemBars())} 尊重 visibility，
     * 不是 {@code getInsetsIgnoringVisibility}）：横屏看视频时状态栏被隐藏，
     * inset 为 0，横幅贴屏幕最顶；竖屏状态栏可见，inset 是它的高度，
     * 横幅紧排其下——和 v0.73.4 及以前用户已认可的竖屏效果一致。
     */
    private static void applyTopInset(View v, int insetTop) {
        WindowManager.LayoutParams lp = lastParams;
        WindowManager wm = windowManager;
        if (v == null || lp == null || wm == null) return;
        lastInsetTop = insetTop;
        int y = insetTop + dp(v.getContext(), TOP_GAP_DP);
        if (lp.y == y) return;
        lp.y = y;
        try {
            wm.updateViewLayout(v, lp);
        } catch (Exception ignore) {
            // 窗口刚被系统回收：下一帧 hide() 会收拾干净
        }
    }

    /**
     * 配置变化（旋屏 / 分屏 / 字体缩放）后重算窗口几何（v0.73.7）。
     *
     * <p>挂在 application context 上的 {@link #CONFIG_CALLBACKS} 在每次配置变化时
     * 调到这里。旋屏不重建横幅窗口，不重算就会带着旧朝向的尺寸：
     * v0.73.7 前横屏响铃直接转竖屏，宽度还是横屏的 {@code widthPixels}，
     * 比竖屏屏宽还大，右侧整块被裁掉（用户截图反馈的第三种情况）。
     *
     * <p>包级可见只为单测能直接驱动；生产路径只有配置变化一条。
     */
    static void relayout() {
        View v = view;
        WindowManager wm = windowManager;
        WindowManager.LayoutParams lp = lastParams;
        if (v == null || wm == null || lp == null) return;
        try {
            applyGeometry(v.getContext(), lp);
            wm.updateViewLayout(v, lp);
        } catch (Exception ignore) {
        }
    }

    /** 只给单测：短提示当前挂着的 View。 */
    static View toastViewForTest() {
        return toastView;
    }

    /** 只给单测：短提示的窗口参数（验「不拦触摸」「贴底」）。 */
    static WindowManager.LayoutParams toastParamsForTest() {
        return toastParams;
    }

    /**
     * 弹一句短提示，2.4 秒后自己消失（v0.55.2）。
     *
     * <p>为什么需要：点「稍后提醒」后横幅立刻撤掉，通知栏那条也要等一两秒才出现，
     * 中间屏幕上什么都没有。用户不确定按钮点上了没有，会再点一次——于是多排一次小睡。
     * 这句提示就是回执。
     *
     * <p>没有悬浮窗权限时退回系统 Toast：样式不统一，但「点了没反应」更糟。
     */
    static void flash(Context ctx, String text) {
        if (text == null || text.isEmpty()) return;
        if (!canFloat(ctx)) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            hideToast();
            View v = LayoutInflater.from(ctx).inflate(R.layout.float_toast, null);
            ((TextView) v.findViewById(R.id.ft_text)).setText(text);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // 不抢焦点、不接收触摸：它只是回执，点它不该有任何反应，
                    // 更不能拦住下面正在操作的界面
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(ctx, TOAST_BOTTOM_DP);
            lp.windowAnimations = R.style.FloatToastAnim;

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
                return;
            }
            wm.addView(v, lp);
            toastView = v;
            toastWm = wm;
            toastParams = lp;
            toastTimeout = FloatingAlarm::hideToast;
            MAIN.postDelayed(toastTimeout, TOAST_MS);
        } catch (Exception e) {
            // 权限被回收 / 系统拒绝：退回 Toast，别让「点了没反应」再发生一次
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        }
    }

    /** 撤掉短提示。没挂过、或已经被系统回收时静默返回。 */
    static void hideToast() {
        if (toastTimeout != null) {
            MAIN.removeCallbacks(toastTimeout);
            toastTimeout = null;
        }
        if (toastView == null || toastWm == null) {
            toastView = null;
            toastWm = null;
            toastParams = null;
            return;
        }
        try {
            toastWm.removeView(toastView);
        } catch (Exception ignore) {
        }
        toastView = null;
        toastWm = null;
        toastParams = null;
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
