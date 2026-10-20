package com.daxz.weekalarm;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioGroup;

/**
 * 「稍后提醒 / 提前提醒」两个设置对话框（v1.26.3 抽出来，设置页与引导页共用）。
 *
 * <p>抽的原因不是代码重复本身，而是这两项最容易被改坏：
 * 「界面显示的档位」和「写进 Prefs 的值」是两套映射（check* 与 *Of），
 * 一旦某一处漏了新档位，用户选了「3 次」实际存成「1 次」，而且界面上完全看不出来。
 * 放一份，两处调用方都不可能各自漂移。
 */
public final class PrefDialogs {

    private PrefDialogs() {
    }

    /** 稍后提醒行文案：「5 分钟 × 3 次」；不限次数显示「不限」。 */
    public static String snoozeText(Context c) {
        int mins = Prefs.getSnoozeMinutes(c);
        int times = Prefs.getSnoozeTimes(c);
        return times == Prefs.SNOOZE_UNLIMITED
                ? mins + " 分钟 × 不限"
                : mins + " 分钟 × " + times + " 次";
    }

    /** 提前提醒行文案：0 = 关闭。 */
    public static String preNoticeText(Context c) {
        int mins = Prefs.getPreNoticeMinutes(c);
        return mins == 0 ? "关闭" : mins >= 60 ? "提前 1 小时" : "提前 " + mins + " 分钟";
    }

    /**
     * 稍后提醒对话框：间隔（3/5/10/15/30 分钟）+ 最多几次（1/2/3/5/不限）。
     *
     * @param onChanged 确定后的回调，调用方用来刷新自己那一行文案
     */
    public static void showSnooze(Context c, Runnable onChanged) {
        View view = LayoutInflater.from(c).inflate(R.layout.dialog_snooze, null);
        RadioGroup rgMinutes = view.findViewById(R.id.rg_snooze_minutes);
        RadioGroup rgTimes = view.findViewById(R.id.rg_snooze_times);
        checkSnoozeMinutes(rgMinutes, Prefs.getSnoozeMinutes(c));
        checkSnoozeTimes(rgTimes, Prefs.getSnoozeTimes(c));
        new RoundedDialog.Builder(c)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setSnoozeMinutes(c, minutesOf(rgMinutes));
                    Prefs.setSnoozeTimes(c, timesOf(rgTimes));
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 提前提醒对话框：关闭 / 5 / 10 / 15 / 30 分钟 / 1 小时。 */
    public static void showPreNotice(Context c, Runnable onChanged) {
        View view = LayoutInflater.from(c).inflate(R.layout.dialog_pre_notice, null);
        RadioGroup rg = view.findViewById(R.id.rg_pre_notice);
        checkPreNotice(rg, Prefs.getPreNoticeMinutes(c));
        new RoundedDialog.Builder(c)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setPreNoticeMinutes(c, preNoticeOf(rg));
                    // v1.18.2 修复：提前通知闹钟是与主闹钟配对注册的，
                    // 改了提前量必须重排一次才生效（此前要等下次响铃 / 重启）
                    if (Prefs.isEnabled(c)) {
                        ScheduleManager.rescheduleAll(c);
                    }
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void checkSnoozeMinutes(RadioGroup rg, int minutes) {
        int id = minutes <= 3 ? R.id.rb_snooze_3
                : minutes <= 5 ? R.id.rb_snooze_5
                : minutes <= 10 ? R.id.rb_snooze_10
                : minutes <= 15 ? R.id.rb_snooze_15
                : R.id.rb_snooze_30;
        rg.check(id);
    }

    private static int minutesOf(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_snooze_3) return 3;
        if (id == R.id.rb_snooze_5) return 5;
        if (id == R.id.rb_snooze_10) return 10;
        if (id == R.id.rb_snooze_15) return 15;
        return 30;
    }

    private static void checkSnoozeTimes(RadioGroup rg, int times) {
        int id = times == Prefs.SNOOZE_UNLIMITED ? R.id.rb_times_unlimited
                : times <= 1 ? R.id.rb_times_1
                : times <= 2 ? R.id.rb_times_2
                : times <= 3 ? R.id.rb_times_3
                : R.id.rb_times_5;
        rg.check(id);
    }

    private static int timesOf(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_times_unlimited) return Prefs.SNOOZE_UNLIMITED;
        if (id == R.id.rb_times_2) return 2;
        if (id == R.id.rb_times_3) return 3;
        if (id == R.id.rb_times_5) return 5;
        return 1;
    }

    private static void checkPreNotice(RadioGroup rg, int minutes) {
        int id = minutes <= 0 ? R.id.rb_pre_off
                : minutes <= 5 ? R.id.rb_pre_5
                : minutes <= 10 ? R.id.rb_pre_10
                : minutes <= 15 ? R.id.rb_pre_15
                : minutes <= 30 ? R.id.rb_pre_30
                : R.id.rb_pre_60;
        rg.check(id);
    }

    private static int preNoticeOf(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_pre_5) return 5;
        if (id == R.id.rb_pre_10) return 10;
        if (id == R.id.rb_pre_15) return 15;
        if (id == R.id.rb_pre_30) return 30;
        if (id == R.id.rb_pre_60) return 60;
        return 0;
    }
}
