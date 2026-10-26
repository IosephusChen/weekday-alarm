package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 二十四节气（v0.70.0 引入，v0.70.1 改成公式计算）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>已知日期对得上天文台（用户截图那天就是 2026-10-23 霜降）；</li>
 *   <li>每年正好 24 个、名字不重复——公式 + 例外表错一位，往往就是某天两个节气、某天没有；</li>
 *   <li>例外表里那 32 条真的被用上了，而不是写着好看。</li>
 * </ol>
 */
public class SolarTermTest {

    /** 2026 年霜降 = 10/23（用户截图那天）、冬至 = 12/22、小寒 = 1/5。 */
    @Test
    public void knownDates2026() {
        assertEquals("霜降", SolarTerm.termOf(LocalDate.of(2026, 10, 23)));
        assertEquals("冬至", SolarTerm.termOf(LocalDate.of(2026, 12, 22)));
        assertEquals("小寒", SolarTerm.termOf(LocalDate.of(2026, 1, 5)));
        assertEquals("立春", SolarTerm.termOf(LocalDate.of(2026, 2, 4)));
        assertEquals("清明", SolarTerm.termOf(LocalDate.of(2026, 4, 5)));
        // 2026 雨水 = 2/18 是例外表里的一条：公式算 19，实际 18
        assertEquals("雨水", SolarTerm.termOf(LocalDate.of(2026, 2, 18)));
        assertNull("公式值 2/19 该被例外表压掉", SolarTerm.termOf(LocalDate.of(2026, 2, 19)));
    }

    /** 闰年 / 平年的边界各抽一个，防止整块算错。 */
    @Test
    public void knownDatesOtherYears() {
        assertEquals("立春", SolarTerm.termOf(LocalDate.of(2024, 2, 4)));
        assertEquals("冬至", SolarTerm.termOf(LocalDate.of(2024, 12, 21)));
        assertEquals("清明", SolarTerm.termOf(LocalDate.of(2025, 4, 4)));
        assertEquals("霜降", SolarTerm.termOf(LocalDate.of(2025, 10, 23)));
        assertEquals("夏至", SolarTerm.termOf(LocalDate.of(2030, 6, 21)));
    }

    /**
     * 闰年的 1、2 月要补一天（公式最典型的坑）。
     *
     * <p>不补的话，小寒 / 大寒 / 立春 / 雨水每 4 年集体标早一天。
     * 2000 年的大寒是 21 日，2024、2028 是 20 日——差这一天正是公式的临界点。
     */
    @Test
    public void leapYearJanuaryFebruaryShifted() {
        assertEquals("小寒", SolarTerm.termOf(LocalDate.of(2000, 1, 6)));
        assertEquals("大寒", SolarTerm.termOf(LocalDate.of(2000, 1, 21)));
        assertEquals("立春", SolarTerm.termOf(LocalDate.of(2000, 2, 4)));
        assertEquals("雨水", SolarTerm.termOf(LocalDate.of(2000, 2, 19)));

        for (int y : new int[]{2024, 2028}) {
            assertEquals(y + " 小寒", "小寒", SolarTerm.termOf(LocalDate.of(y, 1, 6)));
            assertEquals(y + " 大寒", "大寒", SolarTerm.termOf(LocalDate.of(y, 1, 20)));
            assertEquals(y + " 立春", "立春", SolarTerm.termOf(LocalDate.of(y, 2, 4)));
            assertEquals(y + " 雨水", "雨水", SolarTerm.termOf(LocalDate.of(y, 2, 19)));
        }
    }

    /**
     * 覆盖的每一年都必须正好 24 个节气，且名字不重复。
     *
     * <p>「不重复」是关键：算错一天往往会把某个节气挪到另一天，
     * 于是某天出现两个节气、另一天没有——总数仍是 24，只有查重能抓出来。
     *
     * <p>201 年 × 365 天约 7.3 万次调用，纯算术，跑起来不到一秒。
     */
    @Test
    public void everyYearHasExactly24DistinctTerms() {
        for (int year = SolarTerm.firstYear(); year <= SolarTerm.lastYear(); year++) {
            Set<String> seen = new HashSet<>();
            int count = 0;
            LocalDate d = LocalDate.of(year, 1, 1);
            while (d.getYear() == year) {
                String t = SolarTerm.termOf(d);
                if (t != null) {
                    count++;
                    seen.add(t);
                }
                d = d.plusDays(1);
            }
            assertEquals(year + " 年应有 24 个节气", 24, count);
            assertEquals(year + " 年节气不能重复", 24, seen.size());
        }
    }

    /** 覆盖范围外的年份不猜：返回 null，宁可少标也不要标错。 */
    @Test
    public void outsideRangeReturnsNull() {
        assertNull(SolarTerm.termOf(LocalDate.of(1899, 10, 24)));
        assertNull(SolarTerm.termOf(LocalDate.of(2101, 1, 5)));
    }

    /** 普通日子没有节气。 */
    @Test
    public void ordinaryDayReturnsNull() {
        assertNull(SolarTerm.termOf(LocalDate.of(2026, 10, 24)));
        assertNull(SolarTerm.termOf(LocalDate.of(2026, 1, 1)));
        assertNull(SolarTerm.termOf(null));
    }
}
