package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 系统「闹钟」音量的变化监听（v0.91.1）。
 *
 * <p>0.91.0 把滑块接到 {@code STREAM_ALARM} 之后，App 写得进去，却听不到外面改了什么：
 * 用户按音量键弹出系统音量条往下拖，或者去系统设置里调，回到 App 数字还是打开页面那一刻的。
 * {@code SettingsActivity.onResume} 与 {@code GuideActivity.onResume} 都只在进入页面时读一次，
 * 页面开着的时候没有任何通道。
 *
 * <p><b>为什么是轮询，不是广播</b>：音量变化确实会发
 * {@code android.media.VOLUME_CHANGED_ACTION}，但那是 {@code AudioManager} 里的隐藏常量，
 * 部分国产 ROM 并不转发；{@code Settings.System} 那边也只是表级 notifyChange，子 URI 收不到。
 * 这个项目吃过依赖 ROM 行为的亏（0.90.4 那次 {@code performRestart} 撞游标），
 * 所以这里选一条任何机器都成立的路：页面可见期间每 {@value #INTERVAL_MS} 毫秒读一次档位。
 * 一次 {@code getStreamVolume} 是本地 binder 调用，只读一个 int，页面一关就停，开销可以忽略。
 *
 * <p>只在值真的变了才回调——调用方拿到的语义是「外面动过音量了」，不是「又过了一个 400ms」。
 */
final class VolumeWatcher implements Runnable {

    /** 轮询间隔。400ms 已经比人眼感知「跟手」更快，再密没有意义。 */
    static final long INTERVAL_MS = 400L;

    private final Context appContext;
    private final Runnable onChange;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 上一次读到的档位，用它判断这一轮要不要回调。 */
    private int lastLevel;
    private boolean stopped;

    private VolumeWatcher(Context c, Runnable onChange) {
        // 存 application context：页面销毁后 handler 里可能还挂着一条回调，
        // 抓着 Activity 不放就是一次泄漏
        this.appContext = c.getApplicationContext();
        this.onChange = onChange;
        this.lastLevel = AlarmVolume.level(appContext);
    }

    /**
     * 开始盯着。调用方必须在 {@code onPause} 里 {@link #stop()}，否则页面退到后台还在读。
     *
     * @param onChange 音量变了才执行；传 {@code null} 等于只记录不响应（测试用）
     */
    static VolumeWatcher start(Context c, Runnable onChange) {
        VolumeWatcher w = new VolumeWatcher(c, onChange);
        w.handler.postDelayed(w, INTERVAL_MS);
        return w;
    }

    /** 停止。重复调用无害。 */
    void stop() {
        stopped = true;
        handler.removeCallbacks(this);
    }

    @Override
    public void run() {
        if (stopped) return;
        int now = AlarmVolume.level(appContext);
        if (now != lastLevel) {
            lastLevel = now;
            if (onChange != null) onChange.run();
        }
        handler.postDelayed(this, INTERVAL_MS);
    }
}
