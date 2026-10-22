package com.daxz.weekalarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 「锁屏显示」自测（v1.45.0）。
 *
 * <p>为什么要有这个东西：小米 / 荣耀 / OPPO 这些系统里有一个「锁屏显示」（有的叫「锁屏通知」
 * 「锁屏显示应用界面」），管的是「这个应用的界面能不能盖在锁屏上」。它<b>不在 AOSP 的权限体系里</b>，
 * 没有任何公开 API 能读到状态——{@code AppOpsManager} 那条路要反射厂商私有 op 码，
 * 码值随版本错位，读错了会给用户一个假的绿勾，比不显示更糟。
 *
 * <p>所以换成「测行为」：约 6 秒后拉起一个极简页面，它自己看当时锁屏是不是还锁着。
 * 锁着还能显示出来，就说明这项是开的。
 *
 * <p><b>这条路径刻意做得很轻</b>：不注册闹钟、不排班、不发通知、不写 snooze 次数，
 * 也不碰任何用户数据。它唯一会写的就是自测结果本身。测错了最多是显示一句话不对，
 * 绝不会让人漏响或误响。
 */
final class LockProbe {

    /** PendingIntent 的 requestCode。避开闹钟那几段（主闹钟 0~364 / 提前通知 +50000）。 */
    private static final int RC = 0x4C4B;   // 'LK'

    /**
     * 留几秒给用户按电源键。
     *
     * <p>太短来不及（从看到提示到按下电源键，2 秒是紧的）；太长用户会以为没反应、
     * 转而怀疑这个功能坏了。6 秒是「够按一下，又不至于等得不耐烦」。
     */
    static final long DELAY_MS = 6000;

    /**
     * 超过这么久还没结果，就当它没起来。
     *
     * <p>没起来的原因有两种，对用户来说是一样的：「锁屏显示」没开，或者系统拦了
     * 后台启动界面。两种都得去设置里手动确认，所以不区分，统一说「没测到」。
     */
    static final long TIMEOUT_MS = 20000;

    private LockProbe() {
    }

    /**
     * 开始一次自测：先把结果清成「待测」，再安排 6 秒后拉起测试页。
     *
     * <p>用 {@link AlarmManager} 而不是 {@code Handler.postDelayed}：用户点完就要按电源键锁屏，
     * 那时 App 已经退到后台，Handler 所在的进程随时可能被回收，而闹钟是系统级的，能唤醒设备。
     */
    static void start(Context c) {
        Prefs.setLockProbe(c, Prefs.LOCK_PROBE_NONE, System.currentTimeMillis());

        Intent i = new Intent(c, LockProbeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long at = System.currentTimeMillis() + DELAY_MS;
        try {
            if (ScheduleManager.canScheduleExactAlarms(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                // 没有精确闹钟权限就只能给个大概时刻。这是测试，晚几十秒也能接受，
                // 总比什么都不发生强——那样用户只会看到「没测到」，白测一次
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception ignore) {
                // 连 set 都失败（部分 ROM 对后台拉起 Activity 有限制）：不崩，
                // 界面上会显示「没测到」，用户按提示去设置里看
            }
        }
    }

    /** 结果是否已经「等太久没反应」。 */
    static boolean isTimedOut(Context c) {
        long at = Prefs.getLockProbeAt(c);
        return at > 0
                && Prefs.getLockProbeResult(c) == Prefs.LOCK_PROBE_NONE
                && System.currentTimeMillis() - at > TIMEOUT_MS;
    }
}
