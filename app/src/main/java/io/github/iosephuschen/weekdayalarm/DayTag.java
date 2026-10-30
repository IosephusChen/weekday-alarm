package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;

/**
 * 日历格子里那两个字写什么——<b>全项目唯一一处</b>判断的地方（v0.91.8）。
 *
 * <p>抽出来之前，这段优先级在 {@link CalendarPanel} 和 {@link MonthWidgetRender}
 * 里各写了一份，然后各自漂了：
 *
 * <ul>
 *   <li>日历页有「节气」这一层，小部件没有（v0.73.0 特意去掉的，怕节气顶掉班/休）；</li>
 *   <li>日历页最后一级用 {@link WeekSchedule.DayType#label}，小部件自己写
 *       {@code work ? "班" : "休"}——于是法定假「假」在小部件里变成了「休」，
 *       和旁边的普通周末长得一模一样；补班日「补」变成「班」，也丢了信息。</li>
 * </ul>
 *
 * <p>用户 2026-10-30 对着桌面小部件截图问「为什么 10/2 到 10/7 都是休」。那天之后
 * 只有一个答案：两边调用同一个方法，改顺序只改这里。
 *
 * <p>顺序：法定节假日名 &gt; 农历节日 &gt; 节气 &gt; 基督教节日 &gt; 班/休/假/补。
 * 调休补班日一律不覆盖——「补」比任何节日名都要紧，用户得知道那天要上班。
 *
 * <p><b>代价是明确的</b>：节气日在小部件里看不到班/休，只能靠文字色区分。
 * 这条是用户 2026-10-30 选定方案 A 时接受的——节气那种「一整天都不着急看」的
 * 信息，和「今天上不上班」抢同一个格子，用户选了跟日历页一致。
 */
final class DayTag {

    private DayTag() {
    }

    /**
     * @param lunar     农历节日开关
     * @param terms     节气开关
     * @param christian 基督教节日开关
     * @return 两个字（法定假名可能是四个字，如「国庆」是两个字、少数民族节日更长）
     */
    static String of(LocalDate d, WeekSchedule.DayType type,
                     boolean lunar, boolean terms, boolean christian) {
        boolean fillerAllowed = type != WeekSchedule.DayType.MAKEUP_WORK;
        String legal = type == WeekSchedule.DayType.HOLIDAY
                ? HolidayData.holidayName(d) : null;
        if (legal != null) return legal;
        String fest = (lunar && fillerAllowed) ? LunarCalendar.festival(d) : null;
        if (fest != null) return fest;
        String term = (terms && fillerAllowed) ? SolarTerm.termOf(d) : null;
        if (term != null) return term;
        String christ = (christian && fillerAllowed) ? ChristianCalendar.festival(d) : null;
        if (christ != null) return christ;
        return type.label;
    }
}
