package com.daxz.weekalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 每日零点后的组件刷新（v0.77.2）。
 *
 * <p>由 {@link WidgetTicker} 每天挂一条；触发时把三个组件重画一遍
 * （{@link Widgets#updateAll} 末尾会顺带把下一天挂上，所以这里不用再管闹钟）。
 *
 * <p>只做一件事，不放别的逻辑：跨日刷新要足够「笨」，出问题时一眼能看出是它。
 */
public class WidgetTickReceiver extends BroadcastReceiver {

    static final String ACTION_TICK = "com.daxz.weekalarm.action.WIDGET_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        Widgets.updateAll(context.getApplicationContext());
    }
}
