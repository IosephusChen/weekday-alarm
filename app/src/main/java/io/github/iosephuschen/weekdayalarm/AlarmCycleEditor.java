package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.time.LocalDate;

/**
 * 一条闹钟的班表编辑器（v1.2.0 新建，v1.4.0 收成一层壳）。
 *
 * <p>版式与逐天列表在 {@link ScheduleSheet}，这里只负责三件事：按入口给初值、
 * 把「哪些天响」交给它编、把结果转成 {@link WeekSchedule.Rule} 交回调用方。
 *
 * <p><b>为什么不复用 {@link CycleEditor} 那张表</b>：那一页改的是全局「我的班表」，
 * 每行点开是「几点 + 休息」，改动会重排日历、小组件、所有跟随班表的闹钟。
 * 一条闹钟只有一个时刻（{@code Alarm.hour/minute}），逐日时间在这里没有落点——
 * 硬复用会给用户一列能点开、改完却不生效的「几点」，比不给更糟。
 * 所以两张表共用骨架，但闹钟这张<b>不显示时刻列</b>。
 *
 * <p><b>不写 Prefs</b>：所有改动都在内存里，点「确定」才通过 {@link OnSaved} 交给调用方。
 * 这是它和 {@link CycleEditor} 最大的区别——那边只有一个使用者（全局），可以直接落盘；
 * 这边同时有「新建闹钟」「编辑闹钟」「引导页」三个入口，落盘时机各不相同。
 */
public final class AlarmCycleEditor {

    /** 保存回调。拿到的是不可变的 {@link WeekSchedule.Rule}，调用方自己决定存哪。 */
    public interface OnSaved {
        void onSaved(WeekSchedule.Rule rule);
    }

    /** 自定义入口的默认形状：周一到周五上班。 */
    private static final long DEFAULT_WEEK_MASK = 0b0011111L;

    /** 轮班入口的默认形状：4 天一轮，上二休二——最常见的轮班。 */
    private static final int DEFAULT_ROSTER_LEN = 4;
    private static final long DEFAULT_ROSTER_MASK = 0b0011L;

    private AlarmCycleEditor() {
    }

    /**
     * @param week    true = 自定义（锁 7 天），false = 轮班
     * @param initial 初值；null 表示按入口的默认形状起步
     */
    static void show(Context ctx, boolean week, WeekSchedule.Rule initial, OnSaved cb) {
        if (ctx == null) return;

        int len = week ? 7 : defaultLen(initial);
        LocalDate start = week ? CycleEditor.mondayOf(startOf(initial)) : startOf(initial);
        long mask = week ? defaultWeekMask(initial) : defaultRosterMask(initial);
        boolean holiday = defaultHoliday(initial, week);

        ScheduleSheet.show(ctx,
                week ? ScheduleSheet.Role.ALARM_WEEK : ScheduleSheet.Role.ALARM_ROTATION,
                new ScheduleSheet.Seed(len, start, mask, null, holiday),
                edited -> {
                    if (cb == null) return;
                    cb.onSaved(WeekSchedule.Rule.cycle(edited.len, edited.mask, edited.start,
                            null, edited.holiday));
                });
    }

    // ================= 初值 =================

    private static int defaultLen(WeekSchedule.Rule initial) {
        if (initial != null && initial.cycleLen > 0) return initial.cycleLen;
        return DEFAULT_ROSTER_LEN;
    }

    /** 起点：显式给了就用，否则本周一（自定义）或今天（轮班）。 */
    private static LocalDate startOf(WeekSchedule.Rule initial) {
        if (initial != null && initial.cycleStart != null) return initial.cycleStart;
        return LocalDate.now();
    }

    private static long defaultWeekMask(WeekSchedule.Rule initial) {
        if (initial == null || initial.cycleLen <= 0) return DEFAULT_WEEK_MASK;
        // 传进来的若是别家形状（4 天轮班），前 7 位可能大部分是空的。
        // 这里不做「扩展到 7 天」的搬运——用户从轮班切到自定义是换了个说法，
        // 让他从头勾比猜他想要什么更可靠
        if (initial.cycleLen != 7) return DEFAULT_WEEK_MASK;
        long m = initial.cycleMask & 0x7FL;
        return m == 0L ? DEFAULT_WEEK_MASK : m;
    }

    private static long defaultRosterMask(WeekSchedule.Rule initial) {
        if (initial != null && initial.cycleLen > 0) {
            return initial.cycleMask & WeekSchedule.Rule.maskOf(initial.cycleLen);
        }
        return DEFAULT_ROSTER_MASK;
    }

    /**
     * 节假日开关的初值。
     *
     * <p>有初值就照抄（用户上次怎么勾的，这次还是怎么勾）；没有初值才按入口给默认——
     * 轮班不跟（医院、工厂春节照上班），自定义跟（服务业、教培春节真放假）。
     * 和 {@link CycleEditor#initialHoliday} 同一套道理。
     */
    static boolean defaultHoliday(WeekSchedule.Rule initial, boolean week) {
        if (initial != null && initial.cycleLen > 0) return initial.cycleHoliday;
        return week;
    }
}
