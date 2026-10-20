package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

/**
 * 农历换算与农历节日（v1.20）。
 *
 * 这里的期望值全部是公开可查的历法事实（春节 / 中秋 / 端午的公历日期），
 * 用来锁住内置压缩表与换算逻辑——表里错一位，几年的节日会整体偏移，
 * 而单看代码是看不出来的。
 */
public class LunarCalendarTest {

    @Test
    public void springFestival_knownYears() {
        // 正月初一：2022-02-01、2023-01-22、2024-02-10、2025-01-29、2026-02-17
        LocalDate[] days = {
                LocalDate.of(2022, 2, 1),
                LocalDate.of(2023, 1, 22),
                LocalDate.of(2024, 2, 10),
                LocalDate.of(2025, 1, 29),
                LocalDate.of(2026, 2, 17),
        };
        for (LocalDate d : days) {
            LunarCalendar.LunarDate l = LunarCalendar.fromSolar(d);
            assertNotNull(d.toString(), l);
            assertEquals(d.toString(), 1, l.month);
            assertEquals(d.toString(), 1, l.day);
            assertFalse(d.toString(), l.leap);
            assertEquals("春节", LunarCalendar.festival(d));
        }
    }

    @Test
    public void midAutumn_knownYears() {
        // 八月十五：2024-09-17、2025-10-06、2026-09-25
        assertEquals("中秋", LunarCalendar.festival(LocalDate.of(2024, 9, 17)));
        assertEquals("中秋", LunarCalendar.festival(LocalDate.of(2025, 10, 6)));
        assertEquals("中秋", LunarCalendar.festival(LocalDate.of(2026, 9, 25)));
    }

    @Test
    public void otherFestivals_2026() {
        assertEquals("腊八", LunarCalendar.festival(LocalDate.of(2026, 1, 26)));
        assertEquals("除夕", LunarCalendar.festival(LocalDate.of(2026, 2, 16)));
        assertEquals("元宵", LunarCalendar.festival(LocalDate.of(2026, 3, 3)));
        assertEquals("端午", LunarCalendar.festival(LocalDate.of(2026, 6, 19)));
        assertEquals("七夕", LunarCalendar.festival(LocalDate.of(2026, 8, 19)));
        assertEquals("重阳", LunarCalendar.festival(LocalDate.of(2026, 10, 18)));
    }

    @Test
    public void newYearsEve_isLastDayOfTwelfthMonth() {
        // 2026 除夕是腊月廿九（该年腊月为小月），不是固定的「三十」
        LunarCalendar.LunarDate l = LunarCalendar.fromSolar(LocalDate.of(2026, 2, 16));
        assertNotNull(l);
        assertEquals(12, l.month);
        assertEquals(29, l.day);
        assertEquals("除夕", LunarCalendar.festival(LocalDate.of(2026, 2, 16)));
    }

    @Test
    public void nonFestivalDay_hasNoFestivalButHasLunarText() {
        LocalDate d = LocalDate.of(2026, 10, 20);   // 农历九月十一
        assertNull(LunarCalendar.festival(d));
        assertEquals("农历九月十一", LunarCalendar.fullText(d));
    }

    @Test
    public void leapMonth_parsedAndNotTreatedAsFestival() {
        // 2020 闰四月初一 = 2020-05-23；2023 闰二月初一 = 2023-03-22
        LunarCalendar.LunarDate a = LunarCalendar.fromSolar(LocalDate.of(2020, 5, 23));
        assertNotNull(a);
        assertEquals(4, a.month);
        assertEquals(1, a.day);
        assertTrue(a.leap);
        assertEquals("闰四月初一", a.monthDayText());

        LunarCalendar.LunarDate b = LunarCalendar.fromSolar(LocalDate.of(2023, 3, 22));
        assertNotNull(b);
        assertEquals(2, b.month);
        assertTrue(b.leap);

        // 闰月不过节：闰四月初五不是「四月」的节日，也不该被当成普通四月初五
        LocalDate leap4 = LunarCalendar.toSolar(2020, 4, 5, true);
        assertNotNull(leap4);
        LunarCalendar.LunarDate ll = LunarCalendar.fromSolar(leap4);
        assertNotNull(ll);
        assertTrue(ll.leap);
        assertNull(LunarCalendar.festival(leap4));
    }

    @Test
    public void roundTrip_isStableAcrossWholeRange() {
        // 抽样遍历 1901–2099（每 89 天一个点），公历→农历→公历必须回到原日期
        LocalDate d = LocalDate.of(1901, 1, 1);
        LocalDate end = LocalDate.of(2100, 1, 1);
        int checked = 0;
        while (d.isBefore(end)) {
            LunarCalendar.LunarDate l = LunarCalendar.fromSolar(d);
            assertNotNull(d.toString(), l);
            assertEquals(d.toString(), d, LunarCalendar.toSolar(l.year, l.month, l.day, l.leap));
            checked++;
            d = d.plusDays(89);
        }
        assertTrue("抽样点太少，说明循环没跑起来", checked > 800);
    }

    @Test
    public void outOfRange_returnsNullInsteadOfThrowing() {
        assertNull(LunarCalendar.fromSolar(LocalDate.of(1899, 12, 31)));
        // 表到 2100 农历年为止：2101 年 1 月仍属农历 2100 年（腊月），不算越界
        assertEquals(2100, LunarCalendar.fromSolar(LocalDate.of(2101, 1, 1)).year);
        assertNull(LunarCalendar.fromSolar(LocalDate.of(2102, 6, 1)));
        assertNull(LunarCalendar.fromSolar(null));
        assertEquals("", LunarCalendar.fullText(LocalDate.of(1800, 1, 1)));
        assertNull(LunarCalendar.toSolar(1800, 1, 1, false));
        // 非法参数：闰月不存在 / 日期超出该月天数
        assertNull(LunarCalendar.toSolar(2026, 5, 5, true));   // 2026 无闰五月
        assertNull(LunarCalendar.toSolar(2026, 1, 31, false));
    }
}
