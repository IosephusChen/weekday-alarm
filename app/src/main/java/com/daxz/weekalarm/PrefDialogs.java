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

    /** 提前提醒的「值」：0 显示「关闭」。引导页右侧那一列用它。 */
    public static String preNoticeText(Context c) {
        int mins = Prefs.getPreNoticeMinutes(c);
        return mins <= 0 ? "关闭" : leadText(mins);
    }

    /**
     * 提前提醒的副标题（v1.27，设置页开关下方那行）。
     *
     * <p>关着时说的是「这东西开着会怎样」，而不是只复述「关闭」——
     * 后者等于把开关状态念一遍，用户看完仍然不知道开了有什么用。
     *
     * <p>开着时把提前量带出来：一个开关同时承担「开不开」和「开多久」两件事，
     * 副标题就是那个「多久」的答案，不用再点进去看。
     */
    public static String preNoticeSummary(Context c) {
        int mins = Prefs.getPreNoticeMinutes(c);
        return mins <= 0 ? "响铃前先在通知栏提醒一次" : "响铃前 " + durationText(mins) + "提醒";
    }

    /**
     * 纯时长（「1 小时」/「5 分钟」），不带「提前」二字。
     *
     * <p>v1.45.0 拆出来的原因：副标题要读成「响铃前 1 小时提醒」，
     * 直接把 {@link #leadText} 拼进去会变成「响铃前 提前 1 小时 提醒」，叠了两层。
     */
    private static String durationText(int mins) {
        return mins >= 60 ? "1 小时" : mins + " 分钟";
    }

    /** 「提前 1 小时」——对话框档位与引导页右侧那一列用它。 */
    private static String leadText(int mins) {
        return "提前 " + durationText(mins);
    }

    /**
     * 开关式写入提前提醒（v1.27）。
     *
     * <p>打开时回到「上次选过的那个值」，而不是写死默认 1 小时：关掉再打开
     * 还是自己原来那个数，不会被人悄悄改成别的。只有从没设过的人才落到默认值。
     *
     * <p>这里不能读 {@code getPreNoticeMinutes}——关着的时候它返回 0，
     * 拿 0 去当「上次的值」等于每次都回落默认，那句「关掉再打开还是原来的数」
     * 就成了空话。记忆存在 {@code KEY_PRE_NOTICE_LAST}，由 set 时顺带更新。
     */
    public static void setPreNoticeEnabled(Context c, boolean enabled) {
        Prefs.setPreNoticeMinutes(c, enabled ? Prefs.getPreNoticeLastMinutes(c) : 0);
        Reschedule.run(c, null);
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
                    Reschedule.run(c, onChanged);
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

    /**
     * 音量行的文案（v1.49.6）。
     *
     * <p>0 不能一律写「静音（仅震动）」——震动开关是独立的，关掉之后既没声也不震，
     * 还写「仅震动」就是在说假话。用户真机反馈：关了震动，设置页仍显示「静音（仅震动）」。
     */
    public static String volumeText(int v, boolean vibrate) {
        if (v > 0) return v + "%";
        return vibrate ? "静音（仅震动）" : "静音（不震动）";
    }
}
