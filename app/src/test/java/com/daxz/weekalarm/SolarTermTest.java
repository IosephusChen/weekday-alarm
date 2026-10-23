package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 二十四节气表（v1.51.2）。
 *
 * <p>表是「查表」不是「算」——这里守的就是表本身没抄错：
 * 每年必须正好 24 个、名字不能重复、抽样日期与天文台一致。
 *
 * <p>为什么值得单独测：这张表 504 个数字全是手抄进源码的，
 * 错一个就是某年某个月份的某一天多标/漏标一个节气，肉眼极难发现。
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
    }

    /** 闰年 / 平年的边界各抽一个，防止整行抄串。 */
    @Test
    public void knownDatesOtherYears() {
        assertEquals("立春", SolarTerm.termOf(LocalDate.of(2024, 2, 4)));
        assertEquals("冬至", SolarTerm.termOf(LocalDate.of(2024, 12, 21)));
        assertEquals("清明", SolarTerm.termOf(LocalDate.of(2025, 4, 4)));
        assertEquals("霜降", SolarTerm.termOf(LocalDate.of(2025, 10, 23)));
        assertEquals("夏至", SolarTerm.termOf(LocalDate.of(2030, 6, 21)));
    }

    /**
     * 表覆盖的每一年都必须正好 24 个节气，且名字不重复。
     *
     * <p>「不重复」是关键：抄错一位数字往往会把某个节气挪到另一天，
     * 于是某天出现两个节气、另一天没有——总数仍是 24，只有查重能抓出来。
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

    /** 表外年份不猜：返回 null，宁可少标也不要标错。 */
    @Test
    public void outsideTableReturnsNull() {
        assertNull(SolarTerm.termOf(LocalDate.of(2019, 10, 24)));
        assertNull(SolarTerm.termOf(LocalDate.of(2041, 1, 5)));
    }

    /** 普通日子没有节气。 */
    @Test
    public void ordinaryDayReturnsNull() {
        assertNull(SolarTerm.termOf(LocalDate.of(2026, 10, 24)));
        assertNull(SolarTerm.termOf(LocalDate.of(2026, 1, 1)));
        assertNull(SolarTerm.termOf(null));
    }
}
