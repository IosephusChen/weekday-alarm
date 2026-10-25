package com.daxz.weekalarm;

import android.content.Context;

/**
 * 「稍后提醒 / 提前提醒」两个设置对话框（v1.26.3 抽出来，设置页与引导页共用）。
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
     * 稍后提醒间隔选项与 Prefs 值的映射（v1.70.0 起留在代码里，布局已删）。
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
     * 稍后提醒：间隔 + 最多次数（v1.70.0 换成{@link DialogKit#showGroupPick} 单选卡片）。
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

    /** 提前提醒选项与 Prefs 值的映射（v1.70.0 起留在代码里，布局已删）。 */
    private static final String[] PRE_NOTICE_ITEMS = {
            "关闭", "提前 5 分钟", "提前 10 分钟", "提前 15 分钟", "提前 30 分钟", "提前 1 小时"};
    private static final int[] PRE_NOTICE_VALUES = {0, 5, 10, 15, 30, 60};
    private static final String PRE_NOTICE_TIP =
            "提示：到点前会主动弹窗（锁屏也能看到），可以顺手关掉这一次；弹窗本身不响铃、不震动，声音留给真正的闹钟。开启后每个响铃实例会多占一个系统闹钟名额，App 会自动把主闹钟排程上限减半（200），保证不超过 ROM 的 500 硬限。";

    /**
     * 提前提醒对话框：关闭 / 5 / 10 / 15 / 30 分钟 / 1 小时。
     *
     * <p>v1.70.0 换成{@link DialogKit#showGroupPick} 单选卡片（自绘 RadioGroup
     * {@code dialog_pre_notice} 删除）。确定才写 Prefs 并重排——
     * v1.18.2 修复：提前通知闹钟与主闹钟配对注册，改了提前量必须重排才生效。
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
        DialogKit.showGroupPick(c,
                c.getString(R.string.dialog_pre_notice_title),
                c.getString(R.string.dialog_pre_notice_message) + "\n" + PRE_NOTICE_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group(null, PRE_NOTICE_ITEMS, checked)                },
                picked -> {
                    Prefs.setPreNoticeMinutes(c, PRE_NOTICE_VALUES[picked[0]]);
                    Reschedule.run(c, onChanged);
                });
    }

    /**


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
