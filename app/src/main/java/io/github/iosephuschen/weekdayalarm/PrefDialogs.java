package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

/**
 * 「稍后提醒 / 提前提醒」两个设置对话框（v0.40.0 抽出来，设置页与引导页共用）。
 *
 * <p>抽的原因不是代码重复本身，而是这两项最容易被改坏：
 * 「界面显示的档位」和「写进 Prefs 的值」是两套映射（{@code *_ITEMS} 与 {@code *_VALUES}），
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
     * 纯时长（「1 小时」/「5 分钟」），不带「提前」二字。
     *
     * <p>v0.55.3 拆出来的原因：副标题要读成「响铃前 1 小时提醒」，
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
     * 稍后提醒间隔选项与 Prefs 值的映射（v0.76.4 起留在代码里，布局已删）。
     *
     * <p>两组数组必须等长、逐位对应：用户点第 {@code i} 项，
     * 写进 Prefs 的就是 {@code VALUES[i]}。加档位只改这里。
     */
    private static final String[] SNOOZE_MINUTE_ITEMS =
            {"3 分钟", "5 分钟", "10 分钟", "15 分钟", "30 分钟"};
    private static final int[] SNOOZE_MINUTE_VALUES = {3, 5, 10, 15, 30};
    private static final String[] SNOOZE_TIMES_ITEMS =
            {"1 次", "2 次", "3 次", "5 次", "不限次数"};
    private static final int[] SNOOZE_TIMES_VALUES = {1, 2, 3, 5, Prefs.SNOOZE_UNLIMITED};
    private static final String SNOOZE_TIP =
            "提示：次数用完后响铃界面只提供「滑动停止响铃」，需手动关闭；计数按「闹钟 × 日期」分别统计，次日自动重置。";

    /**
     * 稍后提醒：间隔 + 最多次数（v0.76.4 换成{@link DialogKit#showGroupPick} 单选卡片）。
     *
     * <p>自绘 RadioGroup（{@code dialog_snooze}）的单选圈是系统样式，与卡片语言不搭。
     * 设置页与引导页共用这一个方法，两边同时换新视觉。确定才写 Prefs（同旧交互）。
     *
     * @param onChanged 确定后的回调，调用方用来刷新自己那一行文案
     */
    public static void showSnooze(Context c, Runnable onChanged) {
        DialogKit.showGroupPick(c,
                c.getString(R.string.dialog_snooze_title),
                c.getString(R.string.dialog_snooze_message) + "\n" + SNOOZE_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group("间隔", SNOOZE_MINUTE_ITEMS,
                                snoozeIndex(SNOOZE_MINUTE_VALUES, Prefs.getSnoozeMinutes(c), 4)),
                        new DialogKit.Group("最多几次（每次响铃分别计数）", SNOOZE_TIMES_ITEMS,
                                snoozeIndex(SNOOZE_TIMES_VALUES, Prefs.getSnoozeTimes(c), 3)),
                },
                picked -> {
                    Prefs.setSnoozeMinutes(c, SNOOZE_MINUTE_VALUES[picked[0]]);
                    Prefs.setSnoozeTimes(c, SNOOZE_TIMES_VALUES[picked[1]]);
                    if (onChanged != null) onChanged.run();
                });
    }

    /**
     * 值 → 选项下标。精确命中就用它；命中不了（存储里有旧值 / 非常规值）时
     * 取第一个「不小于它」的档位，都比它大就落 {@code fallback}。
     *
     * <p>调用方传的 fallback 是「最后一档」——与旧 {@code checkSnoozeMinutes} /
     * {@code checkSnoozeTimes} 逐档比较链最后的 else 分支同一套语义：
     * 存了个比所有档位都大的数，就显示最大档，而不是跳回默认档。
     */
    private static int snoozeIndex(int[] values, int cur, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == cur) return i;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= cur) return i;
        }
        return fallback;
    }

    /** 提前提醒选项与 Prefs 值的映射（v0.76.4 起留在代码里，布局已删）。 */
    private static final String[] PRE_NOTICE_ITEMS = {
            "关闭", "提前 5 分钟", "提前 10 分钟", "提前 15 分钟", "提前 30 分钟", "提前 1 小时"};
    private static final int[] PRE_NOTICE_VALUES = {0, 5, 10, 15, 30, 60};
    // v0.91.5 改文案：原文写「到点前会主动弹窗（锁屏也能看到）」，两处都不准。
    // 实际是通知栏一条常驻通知——解锁使用中会先滑下一条横幅，锁屏上就是一张普通通知卡片，
    // 既不弹整屏也不点亮屏幕。使用说明一直是对的，只有这句提示没跟上。
    private static final String PRE_NOTICE_TIP =
            "提示：到点前会在通知栏提醒一次，解锁使用中先滑下一条横幅，锁屏上是一张普通通知卡片，"
                    + "不弹整屏也不点亮屏幕；可以顺手关掉这一次。它本身不响铃、不震动，声音留给真正的闹钟。"
                    + "开启后每个响铃实例会多占一个系统闹钟名额，App 会自动把主闹钟排程上限减半（200），"
                    + "保证不超过 ROM 的 500 硬限。";

    /**
     * 提前提醒对话框：关闭 / 5 / 10 / 15 / 30 分钟 / 1 小时。
     *
     * <p>v0.76.4 换成 {@link DialogKit#showGroupPick} 单选卡片（自绘 RadioGroup
     * {@code dialog_pre_notice} 删除）。
     *
     * <p>v0.79.0 从「选完按确定」改成「点一项即生效并关窗」（{@link DialogKit#showPick}）：
     * 它只有一组选项，不需要二次确认；而同组的睡前提醒早就是「点即生效」，
     * 两个弹窗挨着打开却是两套规则，用户会觉得哪个都不顺手。
     * 提前通知闹钟与主闹钟配对注册，改了提前量必须重排才生效（v0.17.1）。
     */
    public static void showPreNotice(Context c, Runnable onChanged) {
        int cur = Prefs.getPreNoticeMinutes(c);
        int checked = 0;
        for (int i = 0; i < PRE_NOTICE_VALUES.length; i++) {
            if (cur > 0 && cur <= PRE_NOTICE_VALUES[i]) {
                checked = i;
                break;
            }
        }
        if (cur > 60) checked = PRE_NOTICE_ITEMS.length - 1;
        DialogKit.showPick(c,
                c.getString(R.string.dialog_pre_notice_title),
                c.getString(R.string.dialog_pre_notice_message) + "\n" + PRE_NOTICE_TIP,
                PRE_NOTICE_ITEMS, checked,
                picked -> {
                    Prefs.setPreNoticeMinutes(c, PRE_NOTICE_VALUES[picked]);
                    Reschedule.run(c, onChanged);
                });
    }

    // 音量文案（v0.91.0）：volumeText(int) 已删。
    //
    // 它原来把「App 内部的音量百分比」拼成 "80%"。现在音量行显示的是系统
    // STREAM_ALARM 的真实档位，百分比由 {@link AlarmVolume#percent} 算，
    // 多一层 DTO 只会让调用方以为那个数字还是可以随便传的。
}
