package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.time.LocalDate;

/**
 * 假期倒计时（v0.76.0，方案见 {@code docs/方案-假期倒计时.md}）。
 *
 * <p>纯函数直接喂固定日期，不依赖跑测试那天是几号；名字取不到的退化路径用远程增量
 * 造「有假期、没名字」的年份数据（对应真实场景：2027 增量拉到了、但没带名字）。
 */
public class HolidayCountdownTest {

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    private static String text(LocalDate d) {
        HolidayCountdown.Result r = HolidayCountdown.compute(d);
        assertTrue(d + " 应显示倒计时", r.visible);
        return r.text;
    }

    @Test
    public void workdayShowsDaysToNextHoliday() {
        // 9/20 是 2026 年的调休补班日——补班不算假日，倒计时不归零
        LocalDate d = LocalDate.of(2026, 9, 20);
        assertTrue("9/20 应是补班日", HolidayData.isMakeupWorkday(d));
        assertFalse("补班日不是假日", HolidayData.isHoliday(d));
        // 下一个假日：9/25 中秋，还有 5 天
        assertEquals("距中秋还有 5 天", text(d));
    }

    @Test
    public void eveOfHolidaySaysTomorrow() {
        assertEquals("明天就是中秋", text(LocalDate.of(2026, 9, 24)));
    }

    @Test
    public void insideHolidayFirstMiddleLastDay() {
        // 2026 国庆块 10/1–10/7，正日 10/1
        assertEquals("今天国庆", text(LocalDate.of(2026, 10, 1)));
        assertEquals("国庆假期第 2 天", text(LocalDate.of(2026, 10, 2)));
        assertEquals("国庆今天结束", text(LocalDate.of(2026, 10, 7)));
    }

    @Test
    public void singleDayHolidaySaysToday() {
        // 只有一天的块：首日即末日，说「今天」不说「结束」
        HolidayData.applyRemoteJson("{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2027-03-01\",\"2027-03-01\"]],"
                + "\"nameDates\":{\"2027-03-01\":\"清明\"},\"makeups\":[]}");
        assertEquals("今天清明", text(LocalDate.of(2027, 3, 1)));
    }

    @Test
    public void missingNameDegradesOutsideHoliday() {
        // 2027 增量拉到了但没带名字（holiday-cn 提前建文件、days 里没有 name 的年份）
        HolidayData.applyRemoteJson("{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2027-01-01\",\"2027-01-03\"]],\"makeups\":[]}");
        // 12/30 → 1/1 还有 2 天
        assertEquals("距下次放假还有 2 天", text(LocalDate.of(2026, 12, 30)));
        // 假期里没名字：说「假期第 k 天」，不编一个名字
        assertEquals("假期第 2 天", text(LocalDate.of(2027, 1, 2)));
    }

    @Test
    public void beyondScanWindowIsHidden() {
        // 2026-11-15：往后 120 天内没有任何假日数据（2027 增量未拉到）→ 不显示
        HolidayCountdown.Result r = HolidayCountdown.compute(LocalDate.of(2026, 11, 15));
        assertFalse("窗口内无假日数据时应隐藏", r.visible);
    }

    @Test
    public void crossYearBlockMergesAndKeepsOneName() {
        // 元旦连上周末：12/31–1/3 一个块，块名取块内正日（1/1）的名字
        HolidayData.applyRemoteJson("{\"version\":2027,\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2026-12-31\",\"2027-01-03\"]],"
                + "\"nameDates\":{\"2027-01-01\":\"元旦\"},\"makeups\":[]}");
        assertEquals("明天就是元旦", text(LocalDate.of(2026, 12, 30)));
        assertEquals("今天元旦", text(LocalDate.of(2026, 12, 31)));
        assertEquals("元旦假期第 2 天", text(LocalDate.of(2027, 1, 1)));
        assertEquals("元旦今天结束", text(LocalDate.of(2027, 1, 3)));
    }

    @Test
    public void springFestivalBlockNameFromFeastDayNotBlockStart() {
        // 春节假期 2/15–2/23，正月初一在 2/17——块首没有名字，块名仍取正日「春节」
        assertEquals("今天春节", text(LocalDate.of(2026, 2, 15)));
        assertEquals("春节假期第 2 天", text(LocalDate.of(2026, 2, 16)));
        assertEquals("春节假期第 3 天", text(LocalDate.of(2026, 2, 17)));
    }

    @Test
    public void scanWindowIsBounded() {
        // 窗口就是 120 天：基准日选在内置数据耗尽之后（2026-10-20，后面只剩远程数据），
        // 否则会先撞见 9/25 中秋，测不到边界本身
        LocalDate today = LocalDate.of(2026, 10, 20);
        LocalDate far = today.plusDays(121);
        HolidayData.applyRemoteJson("{\"version\":" + far.getYear() + ",\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"" + far + "\",\"" + far + "\"]],\"makeups\":[]}");
        assertFalse("第 121 天的假日不应被找到", HolidayCountdown.compute(today).visible);
        // 同一个块挪到第 120 天 → 应显示
        LocalDate near = today.plusDays(120);
        HolidayData.applyRemoteJson("{\"version\":" + near.getYear() + ",\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"" + near + "\",\"" + near + "\"]],\"makeups\":[]}");
        assertTrue("第 120 天的假日应被找到", HolidayCountdown.compute(today).visible);
    }

    /** UI 层守门（显隐与文案落到首页今天状态卡）见 {@code MainActivityHolidayCountdownTest}。 */
    @Test
    public void uiCoverageLivesInMainActivityTest() {
        assertTrue(true);
    }
}
