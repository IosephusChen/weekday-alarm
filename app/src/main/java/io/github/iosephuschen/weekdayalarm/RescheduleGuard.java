package io.github.iosephuschen.weekdayalarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 「重排失败」的自愈守卫（v1.23.31）。
 *
 * <p>为什么需要：{@link ScheduleManager#rescheduleAll} 会先 {@code cancelAll} 再重设全部实例。
 * 也就是说它一旦在中途抛异常，系统里可能一个闹钟都不剩——而三处调用点
 * （响铃后、开机 / 改时间后、时钟跳变丢弃后）原来都是 {@code catch (Throwable ignore)}，
 * 失败既不重试也不留痕。用户那天早上只会发现「闹钟没响」，App 自己毫不知情。
 *
 * <p>守卫做的两件事：
 * <ol>
 *   <li>失败时在 Prefs 里留一个持久标记，并挂一次 60 秒后的一次性重试
 *       （显式 Intent 回 {@link BootReceiver}，不进 manifest）；</li>
 *   <li>提供 {@link #runIfPending}，用户下次打开 App 时若有旧标记就补排一次——
 *       这是「设备不重启、也没有新闹钟」时唯一还能自愈的入口。</li>
 * </ol>
 *
 * <p>重试只挂一次：重试再失败就只留标记，不再续挂。否则一个必然失败的排程
 * 会变成每 60 秒一次的后台闹钟，比原来的静默更糟。
 */
final class RescheduleGuard {

    /** 兜底重试的 action。与 BootReceiver 里那条分支对应，不进 manifest（显式 Intent）。 */
    static final String ACTION_RETRY = "io.github.iosephuschen.weekdayalarm.RESCHEDULE_RETRY";

    /** 60 秒：够系统从那一次失败里缓过来，又赶在用户发现「不响了」之前。 */
    static final long RETRY_DELAY_MS = 60_000L;

    private static final int REQUEST_CODE = 0x5EED;

    /** 要跑的重活。允许抛：守卫负责接住并记账。 */
    interface Work {
        void run(Context app) throws Exception;
    }

    /** 全量重排：闹钟 → 睡前提醒 → 桌面组件。时钟跳变后这两样也已过期，必须一起重算。 */
    static final Work FULL = app -> {
        ScheduleManager.rescheduleAll(app);
        BedtimeReminder.reschedule(app);
        Widgets.updateAll(app);
    };

    private RescheduleGuard() {
    }

    /** 跑一次全量重排；失败留标记并挂重试。返回是否成功。 */
    static boolean runTracked(Context c) {
        return run(c, FULL, true);
    }

    /**
     * 跑 {@code work}，成功清标记，失败留标记。
     *
     * @param allowRetry 失败后是否再挂一次 60 秒重试。重试路径自己传 false，避免链式续挂。
     */
    static boolean run(Context c, Work work, boolean allowRetry) {
        if (c == null) return false;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            if (work != null) work.run(app);
        } catch (Throwable failed) {
            Prefs.setReschedulePending(app, true);
            if (allowRetry) scheduleRetry(app);
            return false;
        }
        Prefs.setReschedulePending(app, false);
        return true;
    }

    /** 有欠账就补排一次（进首页时调用）。返回是否真的跑了。 */
    static boolean runIfPending(Context c) {
        if (c == null || !Prefs.isReschedulePending(c)) return false;
        run(c, FULL, true);
        return true;
    }

    /** 挂一次 60 秒后的重试。挂不上就算了——标记还在，下次开机 / 打开 App 仍会补。 */
    private static void scheduleRetry(Context app) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(app, BootReceiver.class).setAction(ACTION_RETRY);
            PendingIntent pi = PendingIntent.getBroadcast(app, REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // 用 set() 而不是 setExact()：重试晚几十秒没有关系，精确闹钟在 Android 12+
            // 还要单独申请权限，为一次兜底去要那个权限不值得。
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RETRY_DELAY_MS, pi);
        } catch (Throwable noAlarmManager) {
            // 标记已经写了，补排还有别的入口
        }
    }
}
