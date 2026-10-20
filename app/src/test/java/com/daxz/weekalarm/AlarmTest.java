package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 多闹钟模型测试：JSON 往返、Mode 规则过滤、requestCode 唯一性编码。
 * （ScheduleManager 的 requestCode 是私有的，这里用同样的编码公式复算验证不碰撞。）
 */
public class AlarmTest {

    private static final int REQUEST_BASE = 100000;

    /** 与 ScheduleManager 相同的 requestCode 编码：alarmId * 100000 + dayOffset。 */
    private static int requestCode(int alarmId, LocalDate d, LocalDate today) {
        long off = java.time.temporal.ChronoUnit.DAYS.between(today, d);
        if (off < 0) off = 0;
        if (off > 99999) off = 99999;
        return alarmId * REQUEST_BASE + (int) off;
    }

    private static Alarm alarm(int id, int h, int m, String label, Alarm.Mode mode, boolean en) {
        return new Alarm(id, h, m, label, mode, en);
    }

    // ================= JSON 序列化 =================

    @Test
    public void jsonRoundTrip_preservesAllFields() {
        List<Alarm> src = Arrays.asList(
                alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true),
                alarm(2, 12, 30, "午休", Alarm.Mode.DAILY, false),
                alarm(3, 9, 0, "周末懒觉", Alarm.Mode.WEEKEND, true));
        List<Alarm> out = Alarm.fromJson(Alarm.toJson(src));
        assertEquals(3, out.size());
        for (int i = 0; i < src.size(); i++) {
            Alarm a = src.get(i), b = out.get(i);
            assertEquals(a.id, b.id);
            assertEquals(a.hour, b.hour);
            assertEquals(a.minute, b.minute);
            assertEquals(a.label, b.label);
            assertEquals(a.mode, b.mode);
            assertEquals(a.enabled, b.enabled);
        }
    }

    @Test
    public void jsonRoundTrip_emptyList() {
        assertEquals("[]", Alarm.toJson(Arrays.asList()));
        assertTrue(Alarm.fromJson("[]").isEmpty());
    }

    @Test
    public void fromJson_badInput_returnsEmpty() {
        assertTrue(Alarm.fromJson(null).isEmpty());
        assertTrue(Alarm.fromJson("").isEmpty());
        assertTrue(Alarm.fromJson("不是JSON").isEmpty());
        assertTrue(Alarm.fromJson("[{oops}]").isEmpty());
    }

    @Test
    public void fromJson_missingFields_usesDefaults() {
        List<Alarm> out = Alarm.fromJson("[{\"id\":5}]");
        assertEquals(1, out.size());
        Alarm a = out.get(0);
        assertEquals(5, a.id);
        assertEquals(7, a.hour);
        assertEquals(30, a.minute);
        assertEquals("闹钟", a.label);
        assertEquals(Alarm.Mode.WORKDAY, a.mode);
        assertTrue(a.enabled);
    }

    @Test
    public void fromJson_modeOutOfRange_clamps() {
        List<Alarm> out = Alarm.fromJson("[{\"id\":1,\"mode\":99},{\"id\":2,\"mode\":-3}]");
        // 越界一律兜回 WORKDAY。v1.27 起最大是 ONCE，钳过去会造出一个
        // 没有日期、永远不会响的「仅一次」闹钟——兜回默认值才安全
        assertEquals(Alarm.Mode.WORKDAY, out.get(0).mode);   // 99 越界
        assertEquals(Alarm.Mode.WORKDAY, out.get(1).mode);   // 负数越界
    }

    // ================= Mode 规则过滤 =================

    // 基准：2026-01-05 是大周周六（anchor），则 2026-01-05~01-11 大周、01-12~01-18 小周
    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 10);   // 大周周六
    private static final boolean SMALL_REST_SAT = false;

    @Test
    public void workdayMode_followsSchedule() {
        Alarm a = alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true);
        // 大周周五（2026-01-09 是周五）：上班
        assertTrue(a.appliesTo(LocalDate.of(2026, 1, 9), ANCHOR, SMALL_REST_SAT));
        // 大周周日（2026-01-11）：休息
        assertFalse(a.appliesTo(LocalDate.of(2026, 1, 11), ANCHOR, SMALL_REST_SAT));
        // 小周周六（2026-01-17）：上班（小周休周日 → 周六上班）
        assertTrue(a.appliesTo(LocalDate.of(2026, 1, 17), ANCHOR, SMALL_REST_SAT));
        // 小周周日（2026-01-18）：休息
        assertFalse(a.appliesTo(LocalDate.of(2026, 1, 18), ANCHOR, SMALL_REST_SAT));
    }

    @Test
    public void workdayMode_holidayAndMakeup_overrideShift() {
        Alarm a = alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true);
        // 2026-01-01 是周四且是元旦假期：即便大小周判上班也不响
        assertFalse(a.appliesTo(LocalDate.of(2026, 1, 1), ANCHOR, SMALL_REST_SAT));
        // 2026-02-14 是周六且是春节前补班：休息日也响
        assertTrue(a.appliesTo(LocalDate.of(2026, 2, 14), ANCHOR, SMALL_REST_SAT));
    }

    @Test
    public void workdayMode_smallRestSat_flipsWeekend() {
        Alarm a = alarm(1, 7, 0, "起床", Alarm.Mode.WORKDAY, true);
        // 小周休周六时：小周周六（2026-01-17）休息、小周周日（2026-01-18）上班
        assertFalse(a.appliesTo(LocalDate.of(2026, 1, 17), ANCHOR, true));
        assertTrue(a.appliesTo(LocalDate.of(2026, 1, 18), ANCHOR, true));
    }

    @Test
    public void dailyMode_everyDay() {
        Alarm a = alarm(2, 12, 30, "午休", Alarm.Mode.DAILY, true);
        for (int i = 0; i < 14; i++) {
            assertTrue(a.appliesTo(LocalDate.of(2026, 1, 5).plusDays(i), ANCHOR, SMALL_REST_SAT));
        }
        // 节假日也响（每天就是每天）
        assertTrue(a.appliesTo(LocalDate.of(2026, 10, 1), ANCHOR, SMALL_REST_SAT));
    }

    @Test
    public void weekendMode_onlySatSun() {
        Alarm a = alarm(3, 9, 0, "周末", Alarm.Mode.WEEKEND, true);
        assertTrue(a.appliesTo(LocalDate.of(2026, 1, 10), ANCHOR, SMALL_REST_SAT));   // 周六
        assertTrue(a.appliesTo(LocalDate.of(2026, 1, 11), ANCHOR, SMALL_REST_SAT));   // 周日
        assertFalse(a.appliesTo(LocalDate.of(2026, 1, 9), ANCHOR, SMALL_REST_SAT));   // 周五
        assertFalse(a.appliesTo(LocalDate.of(2026, 1, 12), ANCHOR, SMALL_REST_SAT));  // 周一
    }

    // ================= requestCode 唯一性 =================

    @Test
    public void requestCode_uniqueAcrossAlarmsAndDays() {
        LocalDate today = LocalDate.of(2026, 3, 1);
        // 3 个闹钟 × 365 天，全部两两不同
        long seen = 0;
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (int id = 1; id <= 3; id++) {
            for (int i = 0; i < 365; i++) {
                int code = requestCode(id, today.plusDays(i), today);
                codes.add(code);
                seen++;
            }
        }
        assertEquals(seen, codes.size());
    }

    @Test
    public void requestCode_sameAlarmSameDay_stable() {
        LocalDate today = LocalDate.of(2026, 3, 1);
        LocalDate d = today.plusDays(42);
        assertEquals(requestCode(2, d, today), requestCode(2, d, today));
        // 不同闹钟同一天必须不同
        assertNotEquals(requestCode(1, d, today), requestCode(2, d, today));
    }

    @Test
    public void requestCode_horizon365_noOverflowIntoNextAlarm() {
        LocalDate today = LocalDate.of(2026, 3, 1);
        // 365 天 horizon 的最大日偏移是 364，占不满 10 万位，不会串到下一个 alarmId
        int maxOffset = 364;
        int lastOfAlarm1 = 1 * REQUEST_BASE + maxOffset;
        int firstOfAlarm2 = 2 * REQUEST_BASE + 0;
        assertTrue(lastOfAlarm1 < firstOfAlarm2);
    }

    @Test
    public void timeText_formatsTwoDigits() {
        assertEquals("07:05", alarm(1, 7, 5, "x", Alarm.Mode.DAILY, true).timeText());
        assertEquals("00:00", alarm(1, 0, 0, "x", Alarm.Mode.DAILY, true).timeText());
        assertEquals("23:59", alarm(1, 23, 59, "x", Alarm.Mode.DAILY, true).timeText());
    }

    @Test
    public void modeFromOrdinal_toleratesGarbage() {
        assertEquals(Alarm.Mode.WORKDAY, Alarm.Mode.fromOrdinal(0));
        assertEquals(Alarm.Mode.DAILY, Alarm.Mode.fromOrdinal(1));
        assertEquals(Alarm.Mode.WEEKEND, Alarm.Mode.fromOrdinal(2));
        assertEquals(Alarm.Mode.ONCE, Alarm.Mode.fromOrdinal(3));       // v1.27 新增
        assertEquals(Alarm.Mode.WORKDAY, Alarm.Mode.fromOrdinal(99));   // 越界兜回默认
        assertEquals(Alarm.Mode.WORKDAY, Alarm.Mode.fromOrdinal(-1));
    }
}
