package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;

/**
 * 二十四节气（v0.70.0 引入，v0.70.1 改成公式计算）。
 *
 * <p><b>为什么不用内置日期表</b>：v0.70.0 第一版内置了 2020–2040 共 21 年、
 * 504 个日期（1KB 字符串）。用户直接问「为什么用内置数据？还覆盖那么多年？」——
 * 问得对：小部件只显示当月，21 年纯属多余；而 2040 年之后还得再发一版才能继续用。
 * 一张会过期的表，本身就是技术债。
 *
 * <p><b>现在的做法</b>：寿星通用公式 {@code [Y×0.2422 + C] − L}，C 是每个节气
 * 在某个世纪的常数，L 是闰年数；配一张 32 条的例外表修掉公式的已知偏差。
 * 覆盖 1900–2100 共 201 年，4824 个节气日期与寿星天文历（sxtwl）逐日比对全部一致。
 *
 * <p><b>两个必须做的修正</b>：
 * <ol>
 *   <li>闰年的小寒 / 大寒 / 立春 / 雨水（都在 1、2 月）公式会少算一天——
 *       闰日还没到，但 L 已经把它算进去了，要补 1 天。不补的话每 4 年错 4 个节气。</li>
 *   <li>剩下 32 个零星偏差（如 2019 小寒、2021 冬至、2026 雨水）逐条列进
 *       {@link #FIX}，用公式值之外的准确值覆盖。</li>
 * </ol>
 *
 * <p>对比农历（{@link LunarCalendar}）：农历必须查表——朔望月和真太阳位置没有
 * 闭式公式，每年一个 int 的历法表是唯一办法。节气有公式，就不该抄表。
 */
public final class SolarTerm {

    private static final int FIRST_YEAR = 1900;
    private static final int LAST_YEAR = 2100;

    /** 回归年长度的小数部分：一年推进 0.2422 天。 */
    private static final double D = 0.2422;

    /** 按公历月份顺序：1 月小寒、大寒，2 月立春、雨水……12 月大雪、冬至。 */
    private static final String[] NAMES = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    };

    /** 20 世纪（1900–1999）各节气的 C 值，顺序同 {@link #NAMES}。 */
    private static final double[] C20 = {
            6.11, 20.84, 4.6295, 19.4599, 6.3826, 21.4155, 5.59, 20.888,
            6.318, 21.86, 6.5, 22.2, 7.928, 23.65, 8.35, 23.95,
            8.44, 23.822, 9.098, 24.218, 8.218, 23.08, 7.9, 22.6,
    };

    /** 21 世纪（2000–2099）各节气的 C 值。 */
    private static final double[] C21 = {
            5.4055, 20.12, 3.87, 18.73, 5.63, 20.646, 4.81, 20.1,
            5.52, 21.04, 5.678, 21.37, 7.108, 22.83, 7.5, 23.13,
            7.646, 23.042, 8.318, 23.438, 7.438, 22.36, 7.18, 21.94,
    };

    /**
     * 公式的例外修正，成对出现：{@code 年*100 + 节气下标, 正确日期}。
     *
     * <p>32 条，来自 1900–2100 全量比对的结果。这些年份的节气时刻离零点太近，
     * 公式的四舍五入方向正好相反——不加修正就会标错一天。
     * 2100 年四条是因为它虽能被 4 整除、却不是闰年，C 值的世纪交接处也有偏差。
     */
    private static final int[] FIX = {
            190210, 7, 191108, 7, 191402, 4, 191423, 23,
            191504, 6, 192213, 24, 192303, 19, 192512, 8,
            192716, 9, 192811, 22, 194217, 24, 194702, 4,
            194723, 23, 194804, 5, 195123, 23, 197821, 23,
            198023, 22, 198200, 6, 198423, 22, 200214, 8,
            200809, 21, 201612, 7, 201900, 5, 202123, 21,
            202603, 18, 208201, 20, 208919, 23, 208920, 7,
            210002, 4, 210006, 5, 210013, 23, 210023, 22,
    };

    private SolarTerm() {
    }

    /** 这一天是不是节气；是就返回名字（如「霜降」），不是返回 {@code null}。 */
    public static String termOf(LocalDate d) {
        if (d == null) return null;
        int year = d.getYear();
        if (year < FIRST_YEAR || year > LAST_YEAR) return null;

        int month = d.getMonthValue();
        int day = d.getDayOfMonth();
        int base = (month - 1) * 2;
        for (int k = 0; k < 2; k++) {
            int i = base + k;
            if (dayOf(year, i) == day) return NAMES[i];
        }
        return null;
    }

    /** 某年某个节气的公历日（只到「日」，节气在月内是确定的）。 */
    static int dayOf(int year, int index) {
        int key = year * 100 + index;
        for (int k = 0; k < FIX.length; k += 2) {
            if (FIX[k] == key) return FIX[k + 1];
        }
        int yy = year % 100;
        double c = year >= 2000 ? C21[index] : C20[index];
        int day = (int) (yy * D + c) - yy / 4;
        // 闰年的 1、2 月：闰日还没到，但上面的 yy/4 已经算进去了，补回来
        if (index < 4 && isLeap(year)) day++;
        return day;
    }

    private static boolean isLeap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    /** 覆盖范围的起始年，给文档和测试用。 */
    public static int firstYear() {
        return FIRST_YEAR;
    }

    public static int lastYear() {
        return LAST_YEAR;
    }
}
