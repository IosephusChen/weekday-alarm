package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 锁屏显示自测页（v0.55.3；v1.0.3 重排版式）。
 *
 * <p>由 {@link LockProbe} 用 {@link android.app.AlarmManager} 拉起，在用户按下电源键之后
 * 出现两三秒，自己关掉。它要证明的事情只有一件：<b>这个 App 的界面能不能盖在锁屏上</b>。
 * 能盖住，说明系统的「锁屏显示」是开的，响铃时全屏闹钟也就不会被锁屏挡住。
 *
 * <p>为什么用「测行为」而不是读权限：那一项根本不在 AOSP 权限体系里，没有 API 可读
 * （详见 {@link LockProbe} 类注释）。与其反射厂商私有 op 码赌一个可能错的绿勾，
 * 不如让用户亲眼看到它亮起来。
 *
 * <p><b>不做任何业务动作</b>：不响铃、不震动、不发通知、不写排班、不记 snooze 次数。
 * 唯一写入的是自测结果本身。
 *
 * <p>v1.0.3 把「一个 64sp 的 ✓ + 两行字」重排成「圆盘图标 + 结论 + 解释 + 倒计时细线」。
 * 版式上的取舍写在 {@code activity_lock_probe.xml} 的注释里；这里只管两件事：
 * 按状态换颜色，以及让那根细线在页面消失之前刚好走完。
 */
public final class LockProbeActivity extends Activity {

    /**
     * 通过时停留 2.4 秒：够看清结论 + 扫一眼解释，又不至于挡着人解锁。
     *
     * <p>「没测到」那条多留 1.2 秒：它的说明是两句、还带一句操作指引（回引导页再点一次、
     * 这次先按电源键），2.4 秒读不完。两条路径的停留时长分开，是为了让「读得完」这件事
     * 跟着文案长度走，而不是所有情况都取一个折中的数字。
     */
    private static final long STAY_OK_MS = 2400;
    private static final long STAY_WARN_MS = 3600;

    /**
     * 两个状态色。刻意不复用 {@code R.color.success}（#16A34A）：那个绿是按白底调的，
     * 压在 #1B1D2B 上算下来 5.1:1——读得清，但发闷。深底上的图标要更亮一档才「亮得起来」，
     * 这里是 9.7:1，顺带把圆盘底色和底部细线一起撑住。
     */
    private static final int OK_COLOR = 0xFF4ADE80;
    private static final int WARN_COLOR = 0xFFFBBF24;

    /** 页面自己关掉前留一点余量，免得细线刚走到底画面就没了，像被掐断。 */
    private static final long CLOSE_GRACE_MS = 160;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 要盖在锁屏上、并且自己把屏幕点亮。这两件事在 API 27 才有正式方法，
        // minSdk 是 26，所以低版本走废弃的 window flag——它们仍然有效，只是不再推荐
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_lock_probe);

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();

        // 结果写回 Prefs，引导页 onResume 时读出来显示
        Prefs.setLockProbe(this,
                locked ? Prefs.LOCK_PROBE_PASS : Prefs.LOCK_PROBE_UNLOCKED,
                Prefs.getLockProbeAt(this));

        View badge = findViewById(R.id.lp_badge);
        ImageView mark = findViewById(R.id.lp_mark);
        TextView title = findViewById(R.id.lp_title);
        TextView sub = findViewById(R.id.lp_sub);
        View bar = findViewById(R.id.lp_progress);

        long stay;
        if (locked) {
            paint(badge, mark, bar,
                    R.drawable.bg_probe_badge, R.drawable.ic_probe_check, OK_COLOR);
            title.setText("锁屏显示是开着的");
            // 句号收在第二句上：这是两句完整的话，不是一句被折成两行
            sub.setText("这一页能出现在锁屏上，说明闹钟响时也能。");
            stay = STAY_OK_MS;
        } else {
            // 不是失败，是「这次没测到」——用户可能还没来得及按电源键
            paint(badge, mark, bar,
                    R.drawable.bg_probe_badge_warn, R.drawable.ic_probe_bang, WARN_COLOR);
            title.setText("屏幕没锁上");
            // 这段原来 47 个字，在 296dp 宽里折成 3 行、末行只剩「键。」两个字——
            // 一个孤零零的字挂在最后一行，比说少了更难看。砍到 36 字压回两行：
            // 「测试页起来了」这层意思由标题和「测得出来/测不出来」承担，不必再说一遍。
            //
            // v1.23.0：原文写「回到引导页点『测一下』」，可引导页那颗按钮叫「测锁屏响铃」
            // （v0.73.5 改的名）——照做的人会去引导页找一个不存在的按钮。改成按钮真名，
            // 同时把「回到」压成「回」，37 字仍在两行内。
            sub.setText("当时屏幕是解锁状态，测不出来。"
                    + "回引导页点「测锁屏响铃」，这次先按电源键。");
            stay = STAY_WARN_MS;
        }

        // 细线在 stay 毫秒里从左走到右：这一页会自己消失，而「还剩多久」此前完全没交代，
        // 用户正读着字页面就没了。线走完 = 页面关掉，两件事对齐，才不显得是被掐掉的
        bar.setScaleX(0f);
        bar.animate()
                .scaleX(1f)
                .setDuration(stay)
                .setInterpolator(new LinearInterpolator())
                .start();

        new Handler(Looper.getMainLooper()).postDelayed(this::finish, stay + CLOSE_GRACE_MS);
    }

    /** 按状态换掉圆盘底色、图标形状与细线颜色。三者的色必须同源，否则一眼看出是拼的。 */
    private static void paint(View badge, ImageView mark, View bar,
                              int badgeRes, int iconRes, int color) {
        badge.setBackgroundResource(badgeRes);
        mark.setImageResource(iconRes);
        mark.setColorFilter(color, PorterDuff.Mode.SRC_IN);

        Drawable fill = bar.getBackground();
        if (fill == null) return;
        // mutate()：这个 drawable 可能来自资源缓存，不复制一份就把全局那份改了
        fill = fill.mutate();
        if (fill instanceof GradientDrawable) {
            ((GradientDrawable) fill).setColor(color);
        }
        bar.setBackground(fill);
    }
}
