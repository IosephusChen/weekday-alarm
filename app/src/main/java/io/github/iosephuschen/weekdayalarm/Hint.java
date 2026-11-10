package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * 应用内提示条（2026-11-10，A5）。
 *
 * <p><b>为什么不再用系统 Toast</b>：Toast 是 {@code TYPE_TOAST} 系统窗口，三层都管不了——
 * 不知道底下有什么（会压在说明小字上）、不跟着页面走（切页后还飘着，甚至盖到随后弹出的
 * 系统权限框上）、也不能主动收走。录屏里那 6 段全是这个成因。
 *
 * <p>这个提示条是普通的 {@code TextView}，加在 {@code android.R.id.content} 的
 * {@code FrameLayout} 里，于是三件事自动成立：跟着 Activity 走（页面销毁就没了）、
 * 在键盘之上（{@code adjustResize} 会把 content 缩到键盘上沿）、能被主动移除。
 *
 * <p><b>它不拦截触摸</b>：没有 clickable / focusable，滑动手势照常落到下面的页面。
 *
 * <p>没有 Activity 的调用（Service、广播接收器、浮窗）仍走系统 Toast——那种场合没有页面
 * 可依附，硬造一个 WindowManager 浮层只会多一套要维护的东西。
 */
final class Hint {

    /** 默认停留时长。比系统 {@code LENGTH_SHORT}（约 2s）略长：这几个提示都要读一句话。 */
    static final long DEFAULT_MS = 2600L;

    /** 提示条的标记：同一页面只留一条，测试也靠它找。 */
    static final String TAG = "app_hint";
    private static final int FADE_MS = 160;
    private static final int BOTTOM_MARGIN_DP = 24;
    private static final int SIDE_MARGIN_DP = 24;

    private Hint() {
    }

    /** 在页面底部浮一条提示。非 Activity 退化为系统 Toast。 */
    static void show(Context c, CharSequence text) {
        show(c, text, DEFAULT_MS);
    }

    static void show(Context c, CharSequence text, long durationMs) {
        if (c == null || text == null || text.length() == 0) return;
        Activity a = asActivity(c);
        ViewGroup root = a == null ? null : contentOf(a);
        if (root == null) {
            // 没有可依附的页面：退回系统 Toast，好歹要让用户看见
            Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
            return;
        }
        removeNow(root);           // 连续提示只留最新一条，不叠罗汉
        TextView bar = build(a, text);
        root.addView(bar, params(a, bar));
        bar.setAlpha(0f);
        bar.setTranslationY(dp(a, 8));
        bar.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start();
        bar.postDelayed(() -> fadeOut(root, bar), durationMs);
    }

    /** 立刻收走（{@code onPause} 里用：页面都看不见了，别让它等着过期）。 */
    static void cancel(Activity a) {
        ViewGroup root = contentOf(a);
        if (root != null) removeNow(root);
    }

    /**
     * 存一句留给<b>下一个</b>页面说（v5 用）：引导页说完这句就 {@code finish()}，
     * 提示条会跟着页面一起消失，直接在本页浮是看不到的。
     *
     * <p>存静态字段而不是 Intent extra：目标页已经在返回栈里，走的是 {@code onResume}
     * 复用，不重建。一次性——{@link #showPending} 取走即清空。
     */
    static void next(CharSequence text) {
        pending = text;
    }

    /** 目标页 {@code onResume} 里调：有存货就浮出来，没有就什么都不做。 */
    static void showPending(Activity a) {
        if (pending == null) return;
        CharSequence text = pending;
        pending = null;
        // post 一帧：onResume 时 content 还没量过，直接 addView 会贴到左上角闪一下
        View root = a.findViewById(android.R.id.content);
        if (root != null) root.post(() -> show(a, text));
    }

    /** 测试用：有没有留给下一页的话。 */
    static boolean hasPending() {
        return pending != null;
    }

    private static CharSequence pending;

    // ==================== 内部 ====================

    private static Activity asActivity(Context c) {
        if (!(c instanceof Activity)) return null;
        Activity a = (Activity) c;
        if (a.isFinishing()) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && a.isDestroyed()) return null;
        return a;
    }

    private static ViewGroup contentOf(Activity a) {
        if (a == null) return null;
        View v = a.findViewById(android.R.id.content);
        return v instanceof ViewGroup ? (ViewGroup) v : null;
    }

    private static TextView build(Activity a, CharSequence text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(ContextCompat.getColor(a, R.color.white));
        t.setBackgroundResource(R.drawable.bg_hint);
        t.setGravity(Gravity.CENTER);
        int h = dp(a, 16), v = dp(a, 11);
        t.setPadding(h, v, h, v);
        t.setTag(TAG);
        // 4dp 投影：让它一眼是「浮在页面上的一条」，而不是页面里多出来的一行
        t.setElevation(dp(a, 4));
        // 不拦触摸：提示条只是看，不该挡住下面那一行按钮
        t.setClickable(false);
        t.setFocusable(false);
        t.setMaxWidth(Math.max(dp(a, 120),
                a.getResources().getDisplayMetrics().widthPixels - dp(a, SIDE_MARGIN_DP * 2)));
        return t;
    }

    private static FrameLayout.LayoutParams params(Activity a, TextView bar) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(a, BOTTOM_MARGIN_DP);
        return lp;
    }

    private static void fadeOut(ViewGroup root, View bar) {
        if (bar.getParent() != root) return;
        bar.animate().alpha(0f).translationY(dp(bar.getContext(), 8))
                .setDuration(FADE_MS)
                .withEndAction(() -> root.removeView(bar))
                .start();
    }

    /** 立刻摘掉旧的那条（不做退场动画：新的马上要盖上来，动画只会闪一下）。 */
    private static void removeNow(ViewGroup root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View ch = root.getChildAt(i);
            if (TAG.equals(ch.getTag())) {
                ch.animate().cancel();
                root.removeViewAt(i);
            }
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
