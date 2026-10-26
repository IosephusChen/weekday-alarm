package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.Intent;

/**
 * 应用内刷新信号（v0.56.6）。
 *
 * <p>用于「界面没在前台时被别处改了状态」的场景：通知栏的「明天请假」、桌面小组件的
 * 「今天 / 明天不上班」都在 Activity 之外改 Prefs，改完若不通知界面，用户切回 App
 * 会看到旧状态——必须退到桌面再进来才会刷新（因为刷新只发生在 onResume）。
 *
 * <p>用包内显式广播而不是静态持有 Activity 引用：后者在 Activity 被销毁后就是泄漏，
 * 而这个信号本来就是「有人在听才处理，没人听就丢掉」的语义。
 */
public final class UiRefresh {

    public static final String ACTION = "io.github.iosephuschen.weekdayalarm.UI_REFRESH";

    private UiRefresh() {
    }

    /** 广播一次刷新请求；没有 Activity 在监听时静默失败。 */
    public static void request(Context ctx) {
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            app.sendBroadcast(new Intent(ACTION).setPackage(app.getPackageName()));
        } catch (Throwable ignore) {
            // 广播发不出去不影响数据——界面下次 onResume 仍会刷新，只是慢一拍
        }
    }
}
