package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 关机闹钟（v1.26.0）：把「下一次响铃」交给 Qualcomm 的 vendor 应用，由它在关机后
 * 靠 RTC 把手机叫醒。
 *
 * <p>为什么只能这么做：真关机时第三方进程不存在，{@code AlarmManager} 也不存在。
 * 能响的是 ROM 里 {@code com.qualcomm.qti.poweroffalarm}（{@code sharedUserId=android.uid.system}），
 * 它监听下面两条广播、把时刻写进 {@code /sys/class/rtc/rtc0/wakealarm}。
 * 该 Receiver 的保护权限 {@code org.codeaurora.permission.POWER_OFF_ALARM} 是 dangerous 级
 * （不是 signature），所以第三方声明 + 运行时申请即可——F-Droid 上的 BlackyHawky/Clock
 * 与小米框架的 {@code miui.security.SecurityManagerCompat} 走的是同一条路。
 *
 * <p>不是每台机器都有：MTK 的关机闹钟走 kernel 的 {@code RTC_POWEROFF_WAKEUP_MTK}，
 * 由 miui framework 判定，第三方没有入口。所以先 {@link #supported(Context)} 探测，
 * 没有就整条链路静默关掉，什么都不发。
 */
final class PowerOffAlarm {

    static final String VENDOR_PACKAGE = "com.qualcomm.qti.poweroffalarm";
    static final String PERMISSION = "org.codeaurora.permission.POWER_OFF_ALARM";
    static final String ACTION_SET = "org.codeaurora.poweroffalarm.action.SET_ALARM";
    static final String ACTION_CANCEL = "org.codeaurora.poweroffalarm.action.CANCEL_ALARM";
    static final String EXTRA_TIME = "time";

    private PowerOffAlarm() {}

    /** 这台机器有没有那颗 vendor 应用。没有 = 关机闹钟这条路不存在。 */
    static boolean supported(Context c) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(VENDOR_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean granted(Context c) {
        if (c == null) return false;
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 拼一条广播。{@code time} 是 epoch 毫秒——vendor 的 Receiver 用
     * {@code getLongExtra(TIME, DEFAULT)} 读，按毫秒比较，别传秒。
     */
    static Intent build(String action, long millis) {
        Intent i = new Intent(action);
        i.setPackage(VENDOR_PACKAGE);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(EXTRA_TIME, millis);
        return i;
    }

    /**
     * 每次重排后调用一次：{@code next} 是下一次响铃，null 表示已经没有闹钟了。
     *
     * <p>先看支持与授权；不支持/没授权就什么都不做（也不清 {@code power_off_armed}——
     * 用户重新授权后还要靠它撤销上一次）。
     */
    static void sync(Context c, Long next) {
        if (!supported(c) || !granted(c)) return;
        long armed = Prefs.getPowerOffArmed(c);
        if (next == null) {
            // vendor 的 CANCEL 只在 time 与它自己存的时刻相等时生效，所以必须用记下来的那个
            if (armed > 0) {
                c.sendBroadcast(build(ACTION_CANCEL, armed));
                Prefs.setPowerOffArmed(c, 0L);
            }
            return;
        }
        long millis = next;
        if (millis == armed) return;
        c.sendBroadcast(build(ACTION_SET, millis));
        Prefs.setPowerOffArmed(c, millis);
    }
}
