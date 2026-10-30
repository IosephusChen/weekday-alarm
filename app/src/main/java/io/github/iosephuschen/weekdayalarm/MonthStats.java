package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/**
 * 一个月的排班统计（v0.92.0）。
 *
 * <p>这句「本月 20 天班 · 11 天休」原来只存在于 4×4 小部件底部（{@code MonthWidgetRender}
 * 里的一个局部计数），日历页没有。用户 2026-10-30 拿着桌面截图问「这个统计能不能也放到
 * 日历页」。如果两边各写一份，迟早会长出两个不同的数——这个项目已经吃过一次这个亏
 * （班/休标签在日历页和小部件各写一份，后来漂成「假」变「休」）。
 *
 * <p>所以口径只在这里判一次：{@link #countsAsWork} 决定某天算不算上班，
 * 两个界面都调它，各自在自己已有的遍历里累加，不重复扫 42 格。
 */
final class MonthStats {

    private MonthStats() {
    }

    /**
     * 这天算不算「上班」：上班日没请假，或者休息日标了加班。
     *
     * <p>与 42 格的底色同一套判断——格子上是「假」的那天不算班，是「加班」的那天算班。
     */
    static boolean countsAsWork(WeekSchedule.DayType type, boolean vacation, boolean overtime) {
        return DayOffAction.isWorkDay(type) ? !vacation : overtime;
    }

    /**
     * 统计文案。
     *
     * <p>小部件永远显示当月，写「本月」没问题；日历页能翻到 9 月，翻过去还写「本月」
     * 就指着错的东西了，所以只有 {@code ym} 是当前月时才用「本月」。
     */
    static String text(YearMonth ym, LocalDate today, int workDays) {
        int len = ym.lengthOfMonth();
        String head = YearMonth.from(today).equals(ym) ? "本月" : ym.getMonthValue() + " 月";
        return head + " " + workDays + " 天班 · " + (len - workDays) + " 天休";
    }

    /**
     * 从零算整月。给测试和「手上没有现成遍历」的调用方用；
     * 日历页与小部件都复用自己已有的格子循环，不走这里。
     */
    static String text(YearMonth ym, LocalDate today, WeekSchedule.Rule rule,
                       Set<LocalDate> vacations, Set<LocalDate> overtimes) {
        int workDays = 0;
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate d = ym.atDay(day);
            if (countsAsWork(WeekSchedule.dayType(d, rule),
                    vacations.contains(d), overtimes.contains(d))) {
                workDays++;
            }
        }
        return text(ym, today, workDays);
    }
}
