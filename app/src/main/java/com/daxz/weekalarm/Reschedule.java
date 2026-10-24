package com.daxz.weekalarm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把「重排闹钟」这类重活挪出主线程（v1.63.0）。
 *
 * <p>为什么需要：{@link ScheduleManager#rescheduleAll} 会取消并重设上百个系统闹钟，
 * 每次都是跨进程 IPC。项目自述「累计可达数百毫秒到数秒」——同步跑在 UI 线程上，
 * 拨闹钟开关、改设置都会冻结界面，低端机上接近 ANR。广播侧（AlarmReceiver /
 * BootReceiver / BedtimeReceiver）早就 goAsync 了，界面侧原来到处漏。
 *
 * <p>用单线程Executor而不是每次 new Thread：重排要串行，同时拨两个开关会让两次
 * 「取消全部 + 重设全部」交错，中间态可能把刚排上的闹钟又取消掉。
 */
final class Reschedule {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private Reschedule() {
    }

    /** 重排闹钟，完成后回主线程跑 {@code uiDone}。 */
    static void run(Context ctx, Runnable uiDone) {
        // 先取 application context：lambda 会在后台线程跑，别把 Activity 带过去
        final Context app = ctx.getApplicationContext();
        run(ctx, () -> ScheduleManager.rescheduleAll(app), uiDone);
    }

    /**
     * 在后台跑 {@code work}，完成后回主线程跑 {@code uiDone}。
     *
     * <p>{@code work} 里用 application context，避免捕获 Activity；{@code uiDone}
     * 由调用方自己判空 / 判 isFinishing，这里只负责切回主线程。
     */
    static void run(Context ctx, Runnable work, Runnable uiDone) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                if (work != null) work.run();
            } catch (Throwable ignore) {
                // 重排失败不该崩界面；下次响铃 / 重启 / 再改一次设置都会再补
            }
            if (uiDone != null) {
                new Handler(Looper.getMainLooper()).post(uiDone);
            }
        });
    }
}
