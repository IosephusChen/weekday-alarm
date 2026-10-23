package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;

/**
 * 节假日数据源改造（v1.54.0）：内置扩到 20 年、远程换成 holiday-cn 按年增量。
 *
 * <p>要守住的三件事：
 * <ol>
 *   <li>内置覆盖 2007–2026，每一年都有放假安排，不能有空洞；</li>
 *   <li>空年份（holiday-cn 提前建好但 days 为空的明年文件）<b>绝不能</b>清空已有数据；</li>
 *   <li>按年隔离：拉 2027 不影响 2026 的远程修正。</li>
 * </ol>
 */
public class HolidayCnSourceTest {

    @Before
    public void setUp() {
        HolidayData.resetRemoteForTest();
    }

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    // ---------- 内置层：20 年 ----------

    /** 每一年都得有元旦放假，不能有「某年没数据」的空洞。 */
    @Test
    public void builtIn_hasEveryYearFrom2007To2026() {
        for (int y = HolidayData.BUILT_IN_FIRST_YEAR; y <= HolidayData.BUILT_IN_YEAR; y++) {
            assertTrue(y + " 年元旦应放假", HolidayData.isHoliday(LocalDate.of(y, 1, 1)));
        }
        assertEquals("内置起始年", 2007, HolidayData.BUILT_IN_FIRST_YEAR);
        assertEquals("内置最新年", 2026, HolidayData.BUILT_IN_YEAR);
    }

    /** 2007 年五一还是七天黄金周，2015 年有抗战纪念日——两个「和现在不一样」的年份。 */
    @Test
    public void builtIn_historicalYearsMatchTheirOwnArrangement() {
        // 2007：五一 5/1–5/7 七天，4/28、4/29 补班
        assertTrue(HolidayData.isHoliday(LocalDate.of(2007, 5, 7)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2007, 4, 28)));
        assertFalse("5/8 已收假", HolidayData.isHoliday(LocalDate.of(2007, 5, 8)));

        // 2015：抗战胜利 70 周年 9/3–9/5 放假，9/6 补班
        assertTrue(HolidayData.isHoliday(LocalDate.of(2015, 9, 3)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2015, 9, 6)));
        assertNull("纪念日不是七大节日，不该有 2 字简称", HolidayData.holidayName(LocalDate.of(2015, 9, 3)));
    }

    /** 2020 年中秋与国庆撞在 10/1，只标一个名字（国庆优先），不能两个都写。 */
    @Test
    public void builtIn_mergedFestivalKeepsOneName() {
        assertTrue(HolidayData.isHoliday(LocalDate.of(2020, 10, 1)));
        assertEquals("国庆", HolidayData.holidayName(LocalDate.of(2020, 10, 1)));
    }

    /** 内置年份的名字仍是「只标正日」：春节标正月初一，前后假期日显示「假」。 */
    @Test
    public void builtIn_namesStillOnlyOnTheActualDay() {
        assertEquals("春节", HolidayData.holidayName(LocalDate.of(2026, 2, 17)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 2, 15)));
        assertNull(HolidayData.holidayName(LocalDate.of(2026, 2, 23)));
    }

    // ---------- 远程层：holiday-cn 格式 ----------

    private static String cn(String year, String... pairs) {
        StringBuilder sb = new StringBuilder("{\"year\":" + year + ",\"papers\":[],\"days\":[");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append(',');
            String[] p = pairs[i].split("=");   // "2027-01-01=1" 1=放假 0=补班
            sb.append("{\"name\":\"x\",\"date\":\"").append(p[0])
                    .append("\",\"isOffDay\":").append("1".equals(p[1])).append('}');
        }
        return sb.append("]}").toString();
    }

    @Test
    public void holidayCn_parsesDaysAndPushesYear() {
        assertTrue(HolidayData.applyHolidayCnJson(cn("2027",
                "2027-01-01=1", "2027-01-02=1", "2027-01-03=1", "2027-01-04=0")));

        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
        assertFalse("补班日不放假", HolidayData.isHoliday(LocalDate.of(2027, 1, 4)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2027, 1, 4)));
        assertEquals(2027, HolidayData.effectiveYear());
        assertTrue(HolidayData.hasRemote());
        // 内置 2026 完全不受影响
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));
    }

    /**
     * 空年份是这次改造最容易踩的坑：holiday-cn 会提前建好明年的文件但 days 为空。
     * 若把空数组当成「该年无假期」合入，整年会被判成工作日——比不更新更糟。
     */
    @Test
    public void holidayCn_emptyYearNeverClearsExisting() {
        assertTrue(HolidayData.applyHolidayCnJson(cn("2027", "2027-01-01=1")));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));

        String notPublished = "{\"year\":2027,\"papers\":[],\"days\":[]}";
        assertFalse("空年份不该报告有变化", HolidayData.applyHolidayCnJson(notPublished));
        assertTrue("解析成功、只是还没发布，不算失败", HolidayData.lastApplyOk());
        assertTrue("已有数据不能被清空", HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
        assertEquals(2027, HolidayData.effectiveYear());
    }

    /** 拉 2027 不能冲掉 2026 的远程修正——这是按年隔离的意义。 */
    @Test
    public void holidayCn_yearsAreIsolated() {
        // 2026：把 10/1 改成补班（模拟临时调整）
        HolidayData.applyHolidayCnJson(cn("2026", "2026-10-02=1", "2026-10-01=0"));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 10, 1)));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));

        // 再拉 2027
        HolidayData.applyHolidayCnJson(cn("2027", "2027-01-01=1"));

        // 2026 的修正还在，2027 也生效
        assertTrue("2026 的远程修正不能被 2027 冲掉",
                HolidayData.isMakeupWorkday(LocalDate.of(2026, 10, 1)));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
    }

    /** 远程层不带名字；未来年份靠农历层补，不会真的缺名字。 */
    @Test
    public void holidayCn_futureYearNameFallsBackToLunar() {
        // 2027 春节 2/6
        HolidayData.applyHolidayCnJson(cn("2027", "2027-02-06=1", "2027-02-07=1"));
        assertNull("远程层不存名字", HolidayData.holidayName(LocalDate.of(2027, 2, 6)));
        assertEquals("农历层能补上", "春节", LunarCalendar.festival(LocalDate.of(2027, 2, 6)));
    }

    /** 重复合入同一份数据 → 无变化；结构坏掉 → 不算成功，也不动已有数据。 */
    @Test
    public void holidayCn_repeatAndBrokenJson() {
        String json = cn("2027", "2027-01-01=1");
        assertTrue(HolidayData.applyHolidayCnJson(json));
        assertFalse("同一份数据再合一次不该算变化", HolidayData.applyHolidayCnJson(json));
        assertTrue(HolidayData.lastApplyOk());

        assertFalse(HolidayData.applyHolidayCnJson("{ not json"));
        assertFalse("坏数据不算解析成功", HolidayData.lastApplyOk());
        assertTrue("坏数据不能清空已有安排", HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
    }

    /** 没有 year 字段的 JSON 必须拒绝（防止 {"error":"..."} 被当成空数据）。 */
    @Test
    public void holidayCn_missingYearRejected() {
        assertFalse(HolidayData.applyHolidayCnJson("{\"days\":[{\"date\":\"2027-01-01\",\"isOffDay\":true}]}"));
        assertFalse(HolidayData.lastApplyOk());
        assertFalse(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
    }

    /** 日期年份与 year 字段不符的条目要丢掉，不能串到别的年份去。 */
    @Test
    public void holidayCn_foreignYearEntriesDropped() {
        String json = "{\"year\":2027,\"days\":["
                + "{\"date\":\"2027-01-01\",\"isOffDay\":true},"
                + "{\"date\":\"2026-06-01\",\"isOffDay\":true}]}";
        assertTrue(HolidayData.applyHolidayCnJson(json));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
        assertFalse("混进来的 2026 日期应被丢弃", HolidayData.isHoliday(LocalDate.of(2026, 6, 1)));
    }
}
