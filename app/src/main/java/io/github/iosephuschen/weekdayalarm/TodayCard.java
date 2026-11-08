package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 「今天状态卡」与 4×2 桌面小组件共用的文案计算（v0.76.1）。
 *
 * <p>v0.76.1 把 4×2 小组件改成对齐首页信息卡：大字放今天状态、角标放周次、
 * 副信息放「下次什么时候响」。两边必须是同一套算法同一套字，否则同一天
 * 首页说「今天休息」、桌面说「今天上班」，用户只会更疑心哪个错了。
 * 这些计算原来散在 {@link MainActivity#refreshStatus} 里，抽出来两边共用。
 *
 * <p>全部是纯函数（输入显式传，不碰 Context / Prefs / 时钟），JVM 可直接测。
 */
final class TodayCard {

    private TodayCard() {
    }

    /** 大字段：今天请假 / 今天加班 / 今天上班 / 今天休息。 */
    static String statusText(boolean vacation, boolean overtime, boolean work) {
        return statusText(vacation, overtime, work, null);
    }

    /**
     * 同上，但有班次时写「今天夜班」（v1.8.0）。
     *
     * <p>这是班次模型带来的唯一一处「大字变多」：以前只有「上班」两个字，
     * 现在能说出上哪个班。用户脑子里本来就是「今天夜班」，不是「今天上班」。
     *
     * <p>班次名截到 2 字：这块是 4×2 小组件公用的大字，超过 4 个字会换行或缩字号。
     */
    static String statusText(boolean vacation, boolean overtime, boolean work, String shiftName) {
        if (vacation) return "今天请假";
        boolean hasShift = shiftName != null && !shiftName.trim().isEmpty();
        // v1.11.0：加班日有班次就写班次名——「今天夜班」比「今天加班」多答了「上到几点」，
        // 这正是这一年多来「加班日没有班次」那个缺口的最后一公里
        if (overtime && !hasShift) return "今天加班";
        if (!work) return "今天休息";
        if (!hasShift) return "今天上班";
        String n = shiftName.trim();
        return "今天" + (n.length() > 2 ? n.substring(0, 2) : n);
    }

    /** 角标：「小周 · 休1天」这类周次说明（{@link WeekSchedule#describe} 的薄包装）。 */
    static String chipText(WeekSchedule.Rule rule, LocalDate today) {
        return WeekSchedule.describe(rule, today);
    }

    /**
     * 副信息里的「今天是什么日子」前缀——只给「今天有特殊身份」的日子。
     *
     * <p>普通上班日不出前缀：「下次 明天 07:00 起床」比「上班日 · 下次 明天 07:00 起床」
     * 少一层废话。这条原则原本漏了休息日，而休息日在整张卡上说了三遍：
     * 角标「大周 · 休 2 天」→ 大字「今天休息」→ 副标题「休息日 · 下次闹钟 08:00」。
     *
     * <p>v1.1.0 起休息日也不出前缀。剩下角标和大字两处，一个说「这周怎么排」、
     * 一个说「今天上不上班」，各有各的活，不算重复。
     *
     * <p>保留这四类特殊日：请假 / 加班 / 调休补班 / 法定节假日——这些大字说不出来，
     * 「今天上班」这四个字里没有「但今天是法定节假日」这层信息。
     */
    static String dayTagText(WeekSchedule.DayType todayType, boolean vacation,
                             boolean overtime) {
        if (vacation) return "个人请假";
        if (overtime) return "个人加班";
        if (todayType == WeekSchedule.DayType.MAKEUP_WORK) return "调休补班";
        if (todayType == WeekSchedule.DayType.HOLIDAY) return "法定节假日";
        return "";
    }

    /**
     * 「下一次响铃」的完整写法（v0.30.0）：今天只写时分，明天 / 后天加前缀，
     * 更远才补日期和星期——副标题只有一行，能省的字都省掉。
     */
    static String ringText(ScheduleManager.NextAlarm n, LocalDate today) {
        LocalDate d = n.when.toLocalDate();
        long days = ChronoUnit.DAYS.between(today, d);
        String prefix = days == 0 ? "" : days == 1 ? "明天 " : days == 2 ? "后天 "
                : TimeText.monthDayWeek(d) + " ";
        String time = TimeText.clock(n.when.getHour(), n.when.getMinute());
        String label = n.label == null || n.label.isEmpty() ? "" : " " + n.label;
        return prefix + time + label;
    }

    /**
     * 「下一次响铃」那半句，统一写成「下次闹钟 13:40 午休」。
     *
     * <p>v0.80.1 之前这里按「今天 / 以后」分两种说法：当天的闹钟写「下一个 13:40 午休」，
     * 以后的写「下次 明天 07:00 起床」。用户看到桌面上「下一个 13:40 午休」的第一反应是
     * 「下一个什么」——「下一个」是个没有宾语的量词，得自己脑补「闹钟」两个字。
     * 现在两种合成一种：**主语写出来**（闹钟），时间关系交给 ringText 的「明天 / 后天」前缀。
     */
    static String nextTail(ScheduleManager.NextAlarm next, LocalDate today) {
        return "下次闹钟 " + ringText(next, today);
    }

    /**
     * 副信息整行。没有要响的闹钟时把原因说清楚，不留一个不动的时刻
     * 让人误以为闹钟还会响。
     *
     * <p><b>v1.23.11：空态一分为二。</b>{@code next == null} 只说明「窗口里一次都不命中」，
     * 原来不区分「闹钟全关了」和「闹钟开着但班表不响」，一律写「没有启用的闹钟」。
     * 用户 2026-11-08 指出后者的错：自定义班表七天全休，一条开着的「仅上班日」闹钟
     * 就成了「没有启用的」——他打开列表一看，闹钟明明是绿的。
     *
     * @param anyEnabled 列表里是否有启用的闹钟；由调用方算好传进来，保持本方法纯函数
     */
    static String subText(String dayTag, ScheduleManager.NextAlarm next, LocalDate today,
                          boolean work, boolean vacation, boolean anyEnabled) {
        if (vacation) return "个人请假 · 闹钟全部跳过";
        if (next == null) {
            if (work) return "今天上班，但没有要响的闹钟";
            if (!anyEnabled) return "没有启用的闹钟";
            // 闹钟开着，只是规则在这段窗口里一次都不命中。说「这几天」而不是「今天」：
            // nextRing 扫的是整段窗口，能走到这里就说明近期都没有，不止今天。
            return "闹钟开着，但这几天的班表都不响";
        }
        String tail = nextTail(next, today);
        return dayTag.isEmpty() ? tail : dayTag + " · " + tail;
    }

    /**
     * 首页副信息，去掉「下次闹钟 …」那半句（v1.23.9）。
     *
     * <p>首页把具体时刻挪进了倒计时条左侧（「明天 07:30 起床」+「还有 1 天 1 小时」），
     * 副信息再写一遍就是同一句话说两次——用户 2026-11-08 05:30 看对比图时指出的 B1。
     *
     * <p>三个分支各不相同：请假仍是整句（闹钟全跳过是独立信息）；没有下次闹钟时
     * 必须说清原因，不能只剩一个空行；有下次闹钟时只剩「今天是什么日子」，
     * 常日为空字符串，由调用方把整行收掉。
     *
     * <p><b>只给首页用。</b>4×2 小组件没有倒计时条，那边仍调 {@link #subText}，
     * 时刻只能写在这一行里。
     */
    static String subTextWithoutNext(String dayTag, ScheduleManager.NextAlarm next,
                                     LocalDate today, boolean work, boolean vacation,
                                     boolean anyEnabled) {
        if (vacation) return "个人请假 · 闹钟全部跳过";
        if (next == null) return subText(dayTag, null, today, work, vacation, anyEnabled);
        return dayTag;
    }
}
