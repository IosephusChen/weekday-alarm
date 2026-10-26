package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 农历换算与农历节日（v0.24.0）。
 *
 * 纯算术实现，不依赖任何 Android API，也不联网：内置 1900–2100 年的农历压缩表
 * （每年一个 int，见 {@link #LUNAR_INFO}），覆盖 App 的全部使用范围。
 *
 * 表的位含义（每个 int）：
 * <pre>
 *   bit 16      闰月是否 30 天（1 = 大月 30 天，0 = 小月 29 天）
 *   bit 15..4   正月至十二月的天数（1 = 30 天，0 = 29 天）
 *   bit 3..0    闰月月份（0 = 该年无闰月）
 * </pre>
 *
 * 之所以自己算而不是查表列出节日：农历节日每年公历日期都在变（春节可能落在
 * 1 月 21 日 ~ 2 月 20 日之间），硬编码几年就会过期，算出来才能长期正确。
 */
public final class LunarCalendar {

    /** 农历表起点：1900 年正月初一 = 公历 1900-01-31。 */
    private static final LocalDate BASE = LocalDate.of(1900, 1, 31);
    private static final int BASE_YEAR = 1900;
    private static final int MAX_YEAR = 2100;

    private static final int[] LUNAR_INFO = {
            0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
            0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
            0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
            0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
            0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
            0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
            0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
            0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
            0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
            0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
            0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
            0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
            0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
            0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
            0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
            0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
            0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
            0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
            0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
            0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090-2099
            0x0d520                                                                                    // 2100
    };

    private static final String[] MONTH_NAMES = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
    };
    private static final String[] DAY_NAMES = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };

    private LunarCalendar() {
    }

    /** 农历日期（月 1-12，日 1-30，leap = 是否闰月）。 */
    public static final class LunarDate {
        public final int year;
        public final int month;
        public final int day;
        public final boolean leap;

        LunarDate(int year, int month, int day, boolean leap) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.leap = leap;
        }

        /** 「九月初十」/「闰四月初一」。 */
        public String monthDayText() {
            return (leap ? "闰" : "") + MONTH_NAMES[month - 1] + DAY_NAMES[day - 1];
        }

        @Override
        public String toString() {
            return year + "年" + monthDayText();
        }
    }

    // ================= 表查询 =================

    private static boolean validYear(int y) {
        return y >= BASE_YEAR && y <= MAX_YEAR;
    }

    /** 该农历年的闰月月份，0 = 无闰月。 */
    static int leapMonth(int y) {
        if (!validYear(y)) return 0;
        return LUNAR_INFO[y - BASE_YEAR] & 0xf;
    }

    /** 闰月天数；无闰月返回 0。 */
    static int leapMonthDays(int y) {
        if (leapMonth(y) == 0) return 0;
        return ((LUNAR_INFO[y - BASE_YEAR] & 0x10000) != 0) ? 30 : 29;
    }

    /** 农历某年某月（非闰月）的天数：29 或 30。 */
    static int monthDays(int y, int m) {
        if (!validYear(y) || m < 1 || m > 12) return 29;
        return ((LUNAR_INFO[y - BASE_YEAR] & (0x10000 >> m)) != 0) ? 30 : 29;
    }

    /** 农历一年总天数（含闰月）。 */
    static int yearDays(int y) {
        if (!validYear(y)) return 354;
        int sum = 348;   // 12 个月各按 29 天起算
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            if ((LUNAR_INFO[y - BASE_YEAR] & i) != 0) sum++;
        }
        return sum + leapMonthDays(y);
    }

    // ================= 换算 =================

    /** 公历 → 农历。超出 1900–2100 范围时返回 null（不抛异常，调用方按「无农历」处理）。 */
    public static LunarDate fromSolar(LocalDate d) {
        if (d == null) return null;
        long offset = ChronoUnit.DAYS.between(BASE, d);
        if (offset < 0) return null;
        int year = BASE_YEAR;
        while (year <= MAX_YEAR && offset >= yearDays(year)) {
            offset -= yearDays(year);
            year++;
        }
        if (year > MAX_YEAR) return null;

        int leap = leapMonth(year);
        int month = 0;
        boolean isLeap = false;
        for (int m = 1; m <= 12; m++) {
            int days = monthDays(year, m);
            if (offset < days) {
                month = m;
                isLeap = false;
                break;
            }
            offset -= days;
            // 闰月紧跟在同名月之后（闰四月排在四月后面）
            if (leap == m) {
                int ld = leapMonthDays(year);
                if (offset < ld) {
                    month = m;
                    isLeap = true;
                    break;
                }
                offset -= ld;
            }
        }
        if (month == 0) return null;
        return new LunarDate(year, month, (int) offset + 1, isLeap);
    }

    /** 农历 → 公历；参数非法或超出范围时返回 null。 */
    public static LocalDate toSolar(int year, int month, int day, boolean leap) {
        if (!validYear(year) || month < 1 || month > 12 || day < 1) return null;
        if (leap && leapMonth(year) != month) return null;
        int maxDay = leap ? leapMonthDays(year) : monthDays(year, month);
        if (day > maxDay) return null;

        long days = 0;
        for (int y = BASE_YEAR; y < year; y++) days += yearDays(y);
        for (int m = 1; m < month; m++) {
            days += monthDays(year, m);
            if (leapMonth(year) == m) days += leapMonthDays(year);
        }
        if (leap) days += monthDays(year, month);   // 闰月在同名月之后
        days += day - 1L;
        return BASE.plusDays(days);
    }

    // ================= 节日 =================

    /**
     * 农历节日名（2 字，正好填满日历格子的标签位）；不是节日返回 null。
     *
     * 只收传统节日，不含二十四节气——清明等节气日期由 {@link HolidayData} 的
     * 法定安排负责，两套体系不重复。
     *
     * <p>v0.73.0 补了四个：龙抬头（二月初二）、中元（七月十五）、寒衣（十月初一）、
     * 小年（腊月廿三 / 廿四）。原来只有八个，翻日历翻到七月半、十月初一一片空白，
     * 不像正经农历。这四个都是固定农历日期，判断成本为零。
     *
     * <p>两个取舍：
     * <ul>
     *   <li><b>小年两天都显示</b>：北方廿三、南方廿四，App 不区分地区，两天都写「小年」
     *       比只认一天更不容易被说错。腊月若只有廿九天，廿四仍是小年、除夕是廿九，不冲突。</li>
     *   <li><b>龙抬头是三个字</b>：唯一一个超过两字的。没有通用的两字简称（「龙节」「龙头」
     *       都不成词），硬缩反而看不懂，所以照写三字——格子标签是 8.5sp，三字仍在格宽内。</li>
     * </ul>
     */
    public static String festival(LocalDate d) {
        LunarDate l = fromSolar(d);
        if (l == null) return null;
        if (l.leap) return null;   // 闰月不过节
        if (l.month == 1 && l.day == 1) return "春节";
        if (l.month == 1 && l.day == 15) return "元宵";
        if (l.month == 2 && l.day == 2) return "龙抬头";
        if (l.month == 5 && l.day == 5) return "端午";
        if (l.month == 7 && l.day == 7) return "七夕";
        if (l.month == 7 && l.day == 15) return "中元";
        if (l.month == 8 && l.day == 15) return "中秋";
        if (l.month == 9 && l.day == 9) return "重阳";
        if (l.month == 10 && l.day == 1) return "寒衣";
        if (l.month == 12 && l.day == 8) return "腊八";
        if (l.month == 12 && (l.day == 23 || l.day == 24)) return "小年";
        // 除夕 = 腊月最后一天（廿九或三十，随该年腊月大小而定）
        if (l.month == 12 && l.day == monthDays(l.year, 12)) return "除夕";
        return null;
    }

    /** 「农历九月初十」；超出范围返回空串。 */
    public static String fullText(LocalDate d) {
        LunarDate l = fromSolar(d);
        return l == null ? "" : "农历" + l.monthDayText();
    }
}
