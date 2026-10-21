package com.daxz.weekalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

/**
 * 通知栏「稍后再响」卡片上的「取消」按钮（v1.38.0）。
 *
 * <p>单独一个接收器，是因为它要做的既不是「关闭这次闹钟」（{@link PreNoticeReceiver}），
 * 也不是「明天请假」（{@link BedtimeReceiver}）：只撤掉待触发的那一次小睡。
 * 放进任何一个已有接收器都会让那个类的职责变得说不清。
 */
public class SnoozeReceiver extends BroadcastReceiver {

    /** 小睡的目标日期（可能跨零点，与响铃当天不同），取消时要按它算 requestCode。 */
    public static final String EXTRA_DATE = "snooze_date";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !SnoozeNotice.ACTION_CANCEL.equals(intent.getAction())) return;
        int alarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, 0);
        String ds = intent.getStringExtra(EXTRA_DATE);
        LocalDate target;
        try {
            target = ds != null ? LocalDate.parse(ds) : LocalDate.now();
        } catch (Exception e) {
            target = LocalDate.now();
        }
        ScheduleManager.cancelSnooze(context, alarmId, target);
        SnoozeNotice.cancel(context, alarmId);
        SnoozeNotice.toastCancelled(context);
    }
}
