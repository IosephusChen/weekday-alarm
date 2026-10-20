package com.daxz.weekalarm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 排班计算（纯逻辑，无 Android 依赖，可单测）。
 *
 * <p>v1.22 起不再只服务「大小周」，而是支持四种周休制度：
 * <ul>
 *   <li>{@link Mode#BIG_SMALL} 大小周：一周双休、一周单休，14 天循环</li>
 *   <li>{@link Mode#DOUBLE_REST} 双休：每周六、周日都休</li>
 *   <li>{@link Mode#SINGLE_REST_SUN} 单休（周日休）：周六上班、周日休</li>
 *   <li>{@link Mode#SINGLE_REST_SAT} 单休（周六休）：周六休、周日上班</li>
 * </ul>
 *
 * <p>大小周的约定（其余模式不用 anchor）：
 * <ul>
 *   <li>anchor 是一个「大周的周六」。大小周以周六为切换点，每 14 天循环。</li>
 *   <li>大周：周六、周日都休；小周：默认只休周日（周六上班）。</li>
 *   <li>smallRestSat = true 时反过来：小周休周六、周日上班（少数公司如此）。</li>
 * </ul>
 *
 * <p>四种模式共同遵守：法定节假日与调休优先——
 * 调休补班日 → 一定上班；放假日 → 一定休息。
 */
public final class WeekSchedule {

    public enum DayType {
        MAKEUP_WORK("补"),   // 调休补班
        HOLIDAY("假"),       // 法定节假日放假
        WORK("班"),          // 正常上班
        REST("休");          // 正常休息

        public final String label;

        DayType(String label) {
            this.label = label;
        }
    }

    /** 周休制度（v1.22）。顺序即设置页对话框的顺序。 */
    public enum Mode {
        BIG_SMALL("大小周"),
        DOUBLE_REST("双休"),
        SINGLE_REST_SUN("单休 · 周日休"),
        SINGLE_REST_SAT("单休 · 周六休");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        /** 是否需要用 anchor / 大周小周这套 14 天循环。 */
        public boolean needsAnchor() {
            return this == BIG_SMALL;
        }

        /**
         * 从持久化的序号还原。
         *
         * 越界一律退回大小周，而不是夹到最接近的合法值：后者会让 99 这种脏数据
         * 悄悄变成「单休 · 周六休」，用户的排班和闹钟全跟着错。
         * 退回大小周至少与升级前的行为一致。
         */
        public static Mode fromOrdinal(int i) {
            Mode[] vs = values();
            return (i >= 0 && i < vs.length) ? vs[i] : BIG_SMALL;
        }
    }

    /**
     * 一次判断需要的全部上下文：制度 + 大小周的 anchor + 小周休哪天。
     *
     * <p>把三个参数打包传，是因为它们永远一起出现（调度、日历、小组件、睡前提醒都要），
     * 而且旧代码里「取 anchor 忘了取 smallRestSat」这类错配很容易发生。
     * 双休 / 单休下 anchor 与 smallRestSat 被忽略。
     */
    public static final class Rule {
        public final Mode mode;
        public final LocalDate anchor;
        public final boolean smallRestSat;

        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat) {
            this.mode = mode == null ? Mode.BIG_SMALL : mode;
            this.anchor = anchor;
            this.smallRestSat = smallRestSat;
        }

        public static Rule bigSmall(LocalDate anchor, boolean smallRestSat) {
            return new Rule(Mode.BIG_SMALL, anchor, smallRestSat);
        }
    }

    private WeekSchedule() {
    }

    /**
     * 日期所在「自然周（周一 ~ 周日）」的周六。
     *
     * <p>v1.22.1 修正：旧实现用 ISO 周（周日归属下一周），于是每个周日
     * {@code saturdayOf(周日)} 会跳到下周六，界面上「本周是大周 / 小周」和日历里
     * 这一行的班休对不上（周日在大周的格里，标签却说小周）。大小周以周六为切换点，
     * 周日理应属于刚过去的那个周六所在的周。
     */
    public static LocalDate saturdayOf(LocalDate d) {
        return d.plusDays(5L - (d.getDayOfWeek().getValue() - 1L));
    }

    // ================= 一周开始日（v1.22.2，仅影响显示）=================

    private static final String[] LETTERS_MON = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] LETTERS_SUN = {"日", "一", "二", "三", "四", "五", "六"};

    /**
     * 日期所在「显示周」的第一天：按用户设置取周一或周日。
     *
     * <p>只用于日历网格与「本周班表」的排列。排班判定仍以周六为大小周切换点
     * （见 {@link #saturdayOf}），因此这个设置不会改变任何一天的班 / 休。
     */
    public static LocalDate startOfWeek(LocalDate d, boolean sundayFirst) {
        int shift = sundayFirst
                ? d.getDayOfWeek().getValue() % 7          // 周日=0，周一=1 … 周六=6
                : (d.getDayOfWeek().getValue() + 6) % 7;   // 周一=0 … 周日=6
        return d.minusDays(shift);
    }

    /** 日历表头 / 小组件用的 7 个星期字，顺序随一周开始日旋转。 */
    public static String[] weekLetters(boolean sundayFirst) {
        return (sundayFirst ? LETTERS_SUN : LETTERS_MON).clone();
    }

    /** 给定周六是否为大周周六。anchor 必须是大周周六。 */    public static boolean isBigWeekSaturday(LocalDate saturday, LocalDate anchor) {
        long days = ChronoUnit.DAYS.between(anchor, saturday);
        long weeks = Math.floorDiv(days, 7L);
        return Math.floorMod(weeks, 2L) == 0L;
    }

    /** 今天所在周是否为大周。 */
    public static boolean isBigWeekNow(LocalDate today, LocalDate anchor) {
        return isBigWeekSaturday(saturdayOf(today), anchor);
    }

    /** 该日期的类型（节假日优先于周休制度）。 */
    public static DayType dayType(LocalDate d, Rule rule) {
        if (HolidayData.isMakeupWorkday(d)) return DayType.MAKEUP_WORK;
        if (HolidayData.isHoliday(d)) return DayType.HOLIDAY;

        int v = d.getDayOfWeek().getValue();
        if (v <= 5) return DayType.WORK;                    // 周一 ~ 周五，四种制度都上班
        boolean sat = v == 6;

        switch (rule.mode) {
            case DOUBLE_REST:
                return DayType.REST;                        // 周六、周日都休
            case SINGLE_REST_SUN:
                return sat ? DayType.WORK : DayType.REST;   // 周日休，周六上班
            case SINGLE_REST_SAT:
                return sat ? DayType.REST : DayType.WORK;   // 周六休，周日上班
            case BIG_SMALL:
            default:
                if (sat) {
                    if (isBigWeekSaturday(d, rule.anchor)) return DayType.REST;
                    return rule.smallRestSat ? DayType.REST : DayType.WORK;
                }
                return rule.smallRestSat ? DayType.WORK : DayType.REST;
        }
    }

    /** 兼容旧签名：按大小周判断（历史测试与老调用方仍在用）。 */
    public static DayType dayType(LocalDate d, LocalDate anchor, boolean smallRestSat) {
        return dayType(d, Rule.bigSmall(anchor, smallRestSat));
    }

    /** 该日期是否上班。 */
    public static boolean isWorkday(LocalDate d, Rule rule) {
        DayType t = dayType(d, rule);
        return t == DayType.WORK || t == DayType.MAKEUP_WORK;
    }

    /** 兼容旧签名：按大小周判断。 */
    public static boolean isWorkday(LocalDate d, LocalDate anchor, boolean smallRestSat) {
        return isWorkday(d, Rule.bigSmall(anchor, smallRestSat));
    }

    /** 从 from 起（含）最近的一个上班日。 */
    public static LocalDate nextWorkdayOnOrAfter(LocalDate from, Rule rule) {
        for (LocalDate d = from; ; d = d.plusDays(1)) {
            if (isWorkday(d, rule)) return d;
        }
    }

    /** 兼容旧签名：按大小周判断。 */
    public static LocalDate nextWorkdayOnOrAfter(LocalDate from, LocalDate anchor, boolean smallRestSat) {
        return nextWorkdayOnOrAfter(from, Rule.bigSmall(anchor, smallRestSat));
    }

    /**
     * 当前制度的一句话说明，用于主界面、小组件标题。
     * 例：大小周下「大周 · 休2天」；双休下「双休 · 休2天」。
     */
    public static String describe(Rule rule, LocalDate today) {
        switch (rule.mode) {
            case DOUBLE_REST:
                return "双休 · 休2天";
            case SINGLE_REST_SUN:
                return "单休 · 周日休";
            case SINGLE_REST_SAT:
                return "单休 · 周六休";
            case BIG_SMALL:
            default:
                return isBigWeekNow(today, rule.anchor) ? "大周 · 休2天" : "小周 · 休1天";
        }
    }
}
