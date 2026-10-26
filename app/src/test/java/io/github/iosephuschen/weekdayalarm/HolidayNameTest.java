package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 法定节假日的节日名（v0.25.0 起，v0.26.1 改为只标正日）。
 *
 * 规则：名字只挂在「正日」上，连带的假期日一律显示「假」。
 * 10 月 1 日标「国庆」，10 月 2~7 日只标「假」；春节假期从 2/15 起，
 * 但正月初一是 2/17，所以只有 2/17 叫「春节」。
 * 假期本身的天数不受影响——这是这里最需要守住的东西。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HolidayNameTest {

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    @Test
    public void nationalDayOnlyOnOctoberFirst() {
        assertEquals("国庆", HolidayData.holidayName(LocalDate.of(2026, 10, 1)));
        for (int d = 2; d <= 7; d++) {
            LocalDate day = LocalDate.of(2026, 10, d);
            assertTrue("10/" + d + " 仍应是假期", HolidayData.isHoliday(day));
            assertNull("10/" + d + " 不该再显示节日名", HolidayData.holidayName(day));
        }
    }

    @Test
    public void otherHolidaysAlsoOnlyOnTheirActualDay() {
        // 元旦：1/1 正日，1/2、1/3 只是假期
        assertEquals("元旦", HolidayData.holidayName(LocalDate.of(2026, 1, 1)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 1, 2)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 1, 3)));

        // 春节：假期 2/15 起，正月初一是 2/17
        assertEquals("春节", HolidayData.holidayName(LocalDate.of(2026, 2, 17)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 2, 15)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 2, 16)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 2, 23)));

        // 清明：假期 4/4 起，节气正日 4/5
        assertEquals("清明", HolidayData.holidayName(LocalDate.of(2026, 4, 5)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 4, 4)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 4, 6)));

        // 劳动：5/1 正日
        assertEquals("劳动", HolidayData.holidayName(LocalDate.of(2026, 5, 1)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 5, 2)));

        // 端午：6/19 正日
        assertEquals("端午", HolidayData.holidayName(LocalDate.of(2026, 6, 19)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 6, 20)));

        // 中秋：9/25 正日
        assertEquals("中秋", HolidayData.holidayName(LocalDate.of(2026, 9, 25)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 9, 26)));
    }

    @Test
    public void holidaySpansAreUnchanged() {
        // 改名不能顺手改掉假期天数：元旦 3 天、春节 9 天、清明 3 天、
        // 劳动 5 天、端午 3 天、中秋 3 天、国庆 7 天
        assertSpan(LocalDate.of(2026, 1, 1), 3);
        assertSpan(LocalDate.of(2026, 2, 15), 9);
        assertSpan(LocalDate.of(2026, 4, 4), 3);
        assertSpan(LocalDate.of(2026, 5, 1), 5);
        assertSpan(LocalDate.of(2026, 6, 19), 3);
        assertSpan(LocalDate.of(2026, 9, 25), 3);
        assertSpan(LocalDate.of(2026, 10, 1), 7);
    }

    private static void assertSpan(LocalDate start, int days) {
        for (int i = 0; i < days; i++) {
            assertTrue(start.plusDays(i) + " 应在假期内", HolidayData.isHoliday(start.plusDays(i)));
        }
        assertTrue(start.plusDays(days) + " 不应在假期内", !HolidayData.isHoliday(start.plusDays(days)));
    }

    @Test
    public void namesAreExactlyTwoChars() {
        // 日历格子宽度只够 2 字，多一个字会被省略号截断
        LocalDate[] days = {
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 4, 5),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 19), LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 1)
        };
        for (LocalDate d : days) {
            String n = HolidayData.holidayName(d);
            assertEquals(d + " 的名字应正好 2 字：" + n, 2, n.length());
        }
    }

    @Test
    public void nonHolidayHasNoName() {
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 10, 8)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 12, 25)));
    }

    @Test
    public void makeupWorkdayHasNoName() {
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 10, 10)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 9, 20)));
    }

    @Test
    public void remoteRangeNameOnlyOnFirstDay() {
        String json = "{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2026-10-01\",\"2026-10-07\",\"国庆\"]],\"makeups\":[]}";
        HolidayData.applyRemoteJson(json);
        assertEquals("国庆", HolidayData.holidayName(LocalDate.of(2026, 10, 1)));
        // 远程给的区间名字同样只标首日，2~7 号不该跟着变
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 10, 2)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 2)));
    }

    @Test
    public void nameDatesPinpointWhenFirstDayIsNotTheFeast() {
        // 春节正日不在区间首日，数据源可以用 nameDates 精确指定
        String json = "{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2027-02-05\",\"2027-02-13\"]],"
                + "\"nameDates\":{\"2027-02-07\":\"春节\"},\"makeups\":[]}";
        HolidayData.applyRemoteJson(json);
        assertEquals("春节", HolidayData.holidayName(LocalDate.of(2027, 2, 7)));
        assertNull(HolidayData.holidayName(LocalDate.of(2027, 2, 5)));
    }

    @Test
    public void remoteWithoutNameFallsBackToBuiltIn() {
        String json = "{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2026-10-01\",\"2026-10-07\"]],\"makeups\":[]}";
        HolidayData.applyRemoteJson(json);
        assertEquals("国庆", HolidayData.holidayName(LocalDate.of(2026, 10, 1)));
    }

    @Test
    public void remoteRemovalKillsName() {
        String json = "{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2026-10-02\",\"2026-10-07\"]],\"makeups\":[\"2026-10-01\"]}";
        HolidayData.applyRemoteJson(json);
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 10, 1)));
        // 10/2 仍是假期，但正日被剔走后不该再有节日名
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 2)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 10, 2)));
    }

    @Test
    public void remoteNewYearWithoutNameIsStillUsable() {
        String json = "{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2027-01-01\",\"2027-01-03\"]],\"makeups\":[]}";
        HolidayData.applyRemoteJson(json);
        assertNull(HolidayData.holidayName(LocalDate.of(2027, 1, 1)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
    }

    @Test
    public void resetClearsRemoteNames() {
        HolidayData.applyRemoteJson("{\"version\":2027,"
                + "\"holidays\":[[\"2026-10-01\",\"2026-10-07\",\"国庆节\"]],\"makeups\":[]}");
        assertEquals("国庆节", HolidayData.holidayName(LocalDate.of(2026, 10, 1)));
        HolidayData.resetRemoteForTest();
        assertEquals("国庆", HolidayData.holidayName(LocalDate.of(2026, 10, 1)));
    }
}
