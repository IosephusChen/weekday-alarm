package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;

/**
 * 节假日数据层测试：内置数据抽查 + 远程 JSON 合入/覆盖优先级 + 容错。
 */
public class HolidayDataTest {

    @Before
    public void setUp() {
        HolidayData.resetRemoteForTest();
    }

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    // ---------- 内置数据（2026 年，国务院 2025-11-04 发布） ----------

    @Test
    public void builtIn_newYear_isHolidayAndMakeup() {
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 1, 1)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 1, 3)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 1, 4)));
        // 元旦后恢复上班
        assertFalse(HolidayData.isHoliday(LocalDate.of(2026, 1, 5)));
    }

    @Test
    public void builtIn_springFestival_nineDays() {
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 2, 15)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 2, 23)));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2026, 2, 14)));   // 补班日不放假
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 2, 14)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 2, 28)));
    }

    @Test
    public void builtIn_nationalDay_makeupOnSunday() {
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 7)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 10, 10))); // 周六补班
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 9, 20)));  // 周日补班
    }

    @Test
    public void builtIn_ordinaryDay_isNothing() {
        LocalDate d = LocalDate.of(2026, 3, 10);   // 普通周二
        assertFalse(HolidayData.isHoliday(d));
        assertFalse(HolidayData.isMakeupWorkday(d));
    }

    // ---------- 远程数据：新增未来年份 ----------

    @Test
    public void remote_addsNextYear() {
        String json = "{"
                + "\"version\":2027,"
                + "\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2027-01-01\",\"2027-01-03\"]],"
                + "\"makeups\":[\"2027-01-04\"]"
                + "}";
        assertTrue(HolidayData.applyRemoteJson(json));

        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2027, 1, 3)));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2027, 1, 4)));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2027, 1, 4)));
        // 内置 2026 数据不受影响
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));

        assertEquals(2027, HolidayData.effectiveYear());
        assertEquals("2026-11-04", HolidayData.effectivePublished());
        assertTrue(HolidayData.hasRemote());
    }

    @Test
    public void remote_makeupOverridesBuiltInHoliday() {
        // 远程把 2026-01-01（内置为放假）改判为补班：补班优先级最高
        String json = "{"
                + "\"version\":2026,"
                + "\"published\":\"2026-06-01\","
                + "\"holidays\":[],"
                + "\"makeups\":[\"2026-01-01\"]"
                + "}";
        assertTrue(HolidayData.applyRemoteJson(json));
        assertTrue(HolidayData.isMakeupWorkday(LocalDate.of(2026, 1, 1)));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2026, 1, 1)));   // 被补班覆盖
    }

    @Test
    public void remote_singleDayHolidayPair() {
        // holidays 区间对支持起止相同（单日放假）
        String json = "{"
                + "\"version\":2028,"
                + "\"published\":\"2027-11-01\","
                + "\"holidays\":[[\"2028-01-01\",\"2028-01-01\"]],"
                + "\"makeups\":[]"
                + "}";
        assertTrue(HolidayData.applyRemoteJson(json));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2028, 1, 1)));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2028, 1, 2)));
    }

    @Test
    public void remote_sameContentIsNotChanged() {
        String json = "{"
                + "\"version\":2027,"
                + "\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"2027-01-01\",\"2027-01-03\"]],"
                + "\"makeups\":[\"2027-01-04\"]"
                + "}";
        assertTrue(HolidayData.applyRemoteJson(json));
        assertFalse(HolidayData.applyRemoteJson(json));   // 重复合入同一份数据 → 无变化
    }

    // ---------- 容错 ----------

    @Test
    public void remote_brokenJsonKeepsBuiltIn() {
        assertFalse(HolidayData.applyRemoteJson("{ not json"));
        assertFalse(HolidayData.applyRemoteJson(""));
        // 内置数据仍然可用
        assertTrue(HolidayData.isHoliday(LocalDate.of(2026, 10, 1)));
        assertFalse(HolidayData.hasRemote());
        assertEquals(2026, HolidayData.effectiveYear());
    }

    /**
     * v0.23.0：结构合法但没有 version 的 JSON（如 {"error":"not found"}）必须被拒绝，
     * 不能当成「空数据」合入——否则会把已缓存的远程安排清空。
     */
    @Test
    public void remote_jsonWithoutVersion_rejected_andKeepsExisting() {
        String good = "{\"version\":2028,\"published\":\"2027-11-01\","
                + "\"holidays\":[[\"2028-01-01\",\"2028-01-03\"]],"
                + "\"makeups\":[\"2028-01-08\"]}";
        assertTrue(HolidayData.applyRemoteJson(good));
        assertTrue(HolidayData.isHoliday(LocalDate.of(2028, 1, 1)));
        assertTrue(HolidayData.lastApplyOk());

        // 数据源抽风返回了一个结构合法但无关的 JSON
        assertFalse(HolidayData.applyRemoteJson("{\"error\":\"not found\"}"));
        assertFalse("解析结果标志应为失败", HolidayData.lastApplyOk());
        assertTrue("已有远程数据不能被清空", HolidayData.isHoliday(LocalDate.of(2028, 1, 1)));
        assertEquals(2028, HolidayData.effectiveYear());
    }

    /** 解析成功但内容没变时，lastApplyOk 必须为 true（用于区分「无变化」与「解析失败」）。 */
    @Test
    public void remote_unchangedContent_isNotFailure() {
        String json = "{\"version\":2028,\"holidays\":[[\"2028-01-01\",\"2028-01-02\"]]}";
        assertTrue(HolidayData.applyRemoteJson(json));
        assertFalse("重复合入应返回无变化", HolidayData.applyRemoteJson(json));
        assertTrue("无变化不等于解析失败", HolidayData.lastApplyOk());
    }

    @Test
    public void remote_missingFieldsTolerated() {
        // 只有 version，没有 holidays/makeups：合入成功但无日期数据
        assertTrue(HolidayData.applyRemoteJson("{\"version\":2029}"));
        assertFalse(HolidayData.isHoliday(LocalDate.of(2029, 1, 1)));
        assertEquals(2029, HolidayData.effectiveYear());
    }
}
