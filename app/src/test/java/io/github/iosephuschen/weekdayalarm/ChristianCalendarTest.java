package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 基督教节日计算（v0.26.0）。
 *
 * 复活节是这套日期里唯一需要算的：春分后首个满月后的首个星期日。
 * 这里用 2020–2030 的公开日期做基准，再核对由它派生的六个移动节日
 * 落在正确的星期上（圣灰周三、圣枝周日、受难周五、复活周日……），
 * 最后守住「2 字」这个界面约束。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChristianCalendarTest {

    @Test
    public void easterMatchesKnownDates() {
        int[][] known = {
                {2020, 4, 12}, {2021, 4, 4}, {2022, 4, 17}, {2023, 4, 9}, {2024, 3, 31},
                {2025, 4, 20}, {2026, 4, 5}, {2027, 3, 28}, {2028, 4, 16}, {2029, 4, 1},
                {2030, 4, 21}
        };
        for (int[] k : known) {
            assertEquals("复活节 " + k[0], LocalDate.of(k[0], k[1], k[2]),
                    ChristianCalendar.easter(k[0]));
        }
    }

    @Test
    public void easterIsAlwaysSunday() {
        for (int y = 1900; y <= 2100; y++) {
            assertEquals("复活节必须是星期日：" + y, DayOfWeek.SUNDAY,
                    ChristianCalendar.easter(y).getDayOfWeek());
        }
    }

    @Test
    public void movableFeastsFollowEaster2026() {
        // 2026 复活节 4/5
        assertEquals("圣灰", ChristianCalendar.festival(LocalDate.of(2026, 2, 18)));
        assertEquals("圣枝", ChristianCalendar.festival(LocalDate.of(2026, 3, 29)));
        assertEquals("受难", ChristianCalendar.festival(LocalDate.of(2026, 4, 3)));
        assertEquals("复活", ChristianCalendar.festival(LocalDate.of(2026, 4, 5)));
        assertEquals("升天", ChristianCalendar.festival(LocalDate.of(2026, 5, 14)));
        assertEquals("五旬", ChristianCalendar.festival(LocalDate.of(2026, 5, 24)));
    }

    @Test
    public void movableFeastsLandOnExpectedWeekdays() {
        // 教会历法里这几个节日的星期是固定的，算错一天就会露馅
        for (int y = 2020; y <= 2030; y++) {
            LocalDate e = ChristianCalendar.easter(y);
            assertEquals("圣灰星期三 " + y, DayOfWeek.WEDNESDAY, e.minusDays(46).getDayOfWeek());
            assertEquals("圣枝主日 " + y, DayOfWeek.SUNDAY, e.minusDays(7).getDayOfWeek());
            assertEquals("圣周五 " + y, DayOfWeek.FRIDAY, e.minusDays(2).getDayOfWeek());
            assertEquals("升天节 " + y, DayOfWeek.THURSDAY, e.plusDays(39).getDayOfWeek());
            assertEquals("五旬节 " + y, DayOfWeek.SUNDAY, e.plusDays(49).getDayOfWeek());
        }
    }

    @Test
    public void fixedFeasts() {
        assertEquals("主显", ChristianCalendar.festival(LocalDate.of(2026, 1, 6)));
        assertEquals("献主", ChristianCalendar.festival(LocalDate.of(2026, 2, 2)));
        assertEquals("若瑟", ChristianCalendar.festival(LocalDate.of(2026, 3, 19)));
        assertEquals("圣母", ChristianCalendar.festival(LocalDate.of(2026, 8, 15)));
        assertEquals("诸圣", ChristianCalendar.festival(LocalDate.of(2026, 11, 1)));
        assertEquals("无染", ChristianCalendar.festival(LocalDate.of(2026, 12, 8)));
        assertEquals("平安", ChristianCalendar.festival(LocalDate.of(2026, 12, 24)));
        assertEquals("圣诞", ChristianCalendar.festival(LocalDate.of(2026, 12, 25)));
    }

    @Test
    public void ordinaryDaysHaveNoFeast() {
        assertNull(ChristianCalendar.festival(LocalDate.of(2026, 7, 15)));
        assertNull(ChristianCalendar.festival(LocalDate.of(2026, 3, 3)));
        // 复活节次日不是节日（复活节八日庆期属于礼仪期，不做日历标注）
        assertNull(ChristianCalendar.festival(LocalDate.of(2026, 4, 6)));
    }

    @Test
    public void namesAreExactlyTwoChars() {
        for (int y = 2025; y <= 2027; y++) {
            for (LocalDate d = LocalDate.of(y, 1, 1); d.getYear() == y; d = d.plusDays(1)) {
                String f = ChristianCalendar.festival(d);
                if (f != null) {
                    assertEquals("节日名必须 2 字：" + d + " " + f, 2, f.length());
                }
            }
        }
    }

    @Test
    public void yearBoundaryIsNotMisjudged() {
        // 1 月 1 日离当年复活节有 90 天以上，不能被「复活节前后 50 天」的粗筛误命中
        assertNull(ChristianCalendar.festival(LocalDate.of(2026, 1, 1)));
        assertNull(ChristianCalendar.festival(LocalDate.of(2026, 12, 31)));
    }

    @Test
    public void cacheReturnsSameResult() {
        LocalDate a = ChristianCalendar.easter(2026);
        LocalDate b = ChristianCalendar.easter(2026);
        assertEquals(a, b);
        ChristianCalendar.clearCacheForTest();
        assertEquals(a, ChristianCalendar.easter(2026));
    }

    @Test
    public void rejectsYearsBeforeGregorianCalendar() {
        try {
            ChristianCalendar.easter(1500);
            fail("1583 年以前应拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("1583"));
        }
    }
}
