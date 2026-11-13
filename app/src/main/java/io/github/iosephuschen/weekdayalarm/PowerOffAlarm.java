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
        // v1.26.1 R2：这条链路是 vendor 的，权限竞态、ROM 拒收都可能抛异常。
        // 它在 rescheduleAllLocked 里排在 cancelAll 之后、setAlarm 之前，
        // 一旦抛出去就会把整轮排程打断——一条闹钟都不排，界面还不报错。
        try {
            if (!supported(c)) return;
            long armed = Prefs.getPowerOffArmed(c);
            // v1.27.0：总开关关掉时不只是「不再发 SET」，还要把已经交出去的那条撤回来。
            // 否则用户关了开关，关机后照样到点被叫醒——而且那时他没有任何入口能取消。
            // 没授权时撤不掉（vendor 会拒收），那就留着记录，等重新授权后再撤。
            if (!enabled(c)) {
                if (granted(c)) cancelArmed(c, armed);
                return;
            }
            if (!granted(c)) return;
            if (next == null) {
                // vendor 的 CANCEL 只在 time 与它自己存的时刻相等时生效，所以必须用记下来的那个
                cancelArmed(c, armed);
                return;
            }
            long millis = next;
            // 2026-11-14：不再用 millis == armed 短路。广播是 fire-and-forget，上一次 SET
            // 可能被 vendor 拒收或丢失；换机恢复也可能带来一条「本地有记录、新机 RTC 里
            // 不存在」的时刻。每次重排都重发一次 SET（幂等），下一次重排就是自愈机会。
            Prefs.setPowerOffArmed(c, millis);
            c.sendBroadcast(build(ACTION_SET, millis));
        } catch (Throwable ignored) {
            // 关机闹钟是附加能力：它失败不该影响主排程
        }
    }

    /**
     * 总开关的当前值（v1.27.1）。
     *
     * <p>为什么不直接读 {@link Prefs#powerOffEnabled}：那个键是 v1.27.0 才有的。v1.26.x
     * 的用户升级上来时键不存在、默认读到 {@code false}，于是升级后第一次重排就会把人家
     * 已经交给 RTC 的那条撤掉——用户什么都没做，关机闹钟就静默失效了。
     * 「没有这个键」等于「那一版里他已经授权并开着」，所以按 {@code granted()} 迁移一次。
     * 用户主动关掉的情况键是存在的（值为 false），不会走到这条分支上来。
     */
    static boolean enabled(Context c) {
        if (Prefs.hasPowerOffEnabled(c)) return Prefs.powerOffEnabled(c);
        boolean on = granted(c);
        Prefs.setPowerOffEnabled(c, on);
        return on;
    }

    /** 撤掉已交给 vendor 的那一条。没交过就什么都不做。 */
    static void cancelArmed(Context c, long armed) {
        if (armed <= 0) return;
        // 2026-11-14：发完不立刻清零。CANCEL 也是 fire-and-forget，丢了就再无凭据；
        // 保留时刻，后续每次重排都会再发一次 CANCEL（幂等），直到新的 SET 覆盖它。
        c.sendBroadcast(build(ACTION_CANCEL, armed));
    }
}
