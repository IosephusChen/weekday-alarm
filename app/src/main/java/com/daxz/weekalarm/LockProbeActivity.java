package com.daxz.weekalarm;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 锁屏显示自测页（v0.55.3）。
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
 */
public final class LockProbeActivity extends Activity {

    /** 停留多久自己关掉：够看清一句话，又不至于挡着人解锁。 */
    private static final long STAY_MS = 2400;

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

        TextView mark = findViewById(R.id.lp_mark);
        TextView title = findViewById(R.id.lp_title);
        TextView sub = findViewById(R.id.lp_sub);

        if (locked) {
            mark.setText("✓");
            mark.setTextColor(0xFF4ADE80);
            title.setText("锁屏显示是开着的");
            sub.setText("这一页能出现在锁屏上，说明闹钟响时也能。");
        } else {
            // 不是失败，是「这次没测到」——用户可能还没来得及按电源键
            mark.setText("!");
            mark.setTextColor(0xFFFBBF24);
            title.setText("屏幕没锁上");
            sub.setText("测试页起来了，但当时是解锁状态，测不出来。"
                    + "回到引导页点「测一下」，这次记得先按电源键。");
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::finish, STAY_MS);
    }
}
