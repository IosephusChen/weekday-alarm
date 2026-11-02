package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

/**
 * 「N 天一轮」编辑表（v1.4.0）。
 *
 * <p>这张表原来有两个实现：全局「我的班表」那份每天带时刻，一条闹钟那份只管哪天响。
 * 合并的边界就在这两个差异上——所以下面盯的都是「合了骨架之后，差异还在不在」：
 * 时刻该跟着周期走的有没有走，闹钟那张会不会凭空多出一列时刻。
 */
public class ScheduleSheetTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    // ================= 统计 =================

    @Test
    public void summaryCountsWorkAndRest() {
        assertEquals("共 4 天 · 上班 2 天 · 休息 2 天",
                ScheduleSheet.summaryOf(4, 0b0011L));
    }

    /**
     * 七天全上班：说「休息 0 天」读着别扭，而且没说清后果——这张表意味着闹钟天天响。
     * 与 {@code WeekSchedule.describe} 的改法保持一致。
     */
    @Test
    public void summarySaysEveryDayWhenNoRest() {
        assertEquals("共 7 天 · 每天都上班", ScheduleSheet.summaryOf(7, 0b1111111L));
    }

    @Test
    public void summarySaysEveryDayRestWhenNoWork() {
        assertEquals("共 7 天 · 每天都休", ScheduleSheet.summaryOf(7, 0L));
    }

    // ================= 改周期 =================

    /** 时刻必须跟着周期一起搬：7 天扩到 14 天，第 8 天要接上第 1 天的 07:30。 */
    @Test
    public void resizeCarriesTimesAlong() {
        int[] times = {450, 480, -1, -1, 510, -1, -1};
        ScheduleSheet.Seed s = new ScheduleSheet.Seed(7, MON, 0b0010111L, times, false);

        ScheduleSheet.resize(s, 14);

        assertEquals(14, s.times.length);
        assertEquals("第 8 天接第 1 天", 450, s.times[7]);
        assertEquals("第 9 天接第 2 天", 480, s.times[8]);
        assertEquals("休息日照旧是 -1", -1, s.times[9]);
    }

    /** 闹钟那张表没有时刻，扩周期时不能凭空造一个数组出来。 */
    @Test
    public void resizeLeavesNullTimesForAlarmSheet() {
        ScheduleSheet.Seed s = new ScheduleSheet.Seed(7, MON, 0b0011111L, null, false);

        ScheduleSheet.resize(s, 14);

        assertEquals(14, s.len);
        assertFalse("闹钟那张表不该有时刻数组", s.times != null);
    }

    // ================= 时刻规整 =================

    /**
     * 上班日没有时刻时补 07:30，不是 00:00。
     *
     * <p>补 0 会让新建的班表每天显示「00:00 上班」——看着像闹钟设在半夜，
     * 真接上「按班表时间响」之后更要出事。
     */
    @Test
    public void normalizeFillsDefaultTimeForWorkDays() {
        int[] out = ScheduleSheet.normalizeTimes(new int[]{-1, -1, -1}, 3, 0b101L);
        assertEquals(ScheduleSheet.DEFAULT_MIN, out[0]);
        assertEquals("休息日保持 -1", -1, out[1]);
        assertEquals(ScheduleSheet.DEFAULT_MIN, out[2]);
    }

    @Test
    public void normalizeKeepsExistingTimes() {
        int[] out = ScheduleSheet.normalizeTimes(new int[]{420, 0}, 2, 0b11L);
        assertEquals(420, out[0]);
        assertEquals("0 点是个合法时刻，不能被当成没填", 0, out[1]);
    }

    // ================= 两种角色的差异 =================

    /**
     * 副标题必须说清改动影响范围：全局那张改的是整台手机的班表，
     * 闹钟那张只对这一条生效。这两句话合并时最容易被压成一句。
     */
    @Test
    public void alarmRolesSayTheyOnlyAffectThisAlarm() {
        assertTrue(ScheduleSheet.Role.ALARM_WEEK.sub().contains("只对这一条闹钟生效"));
        assertTrue(ScheduleSheet.Role.ALARM_ROTATION.sub().contains("只对这一条闹钟生效"));
        assertFalse(ScheduleSheet.Role.GLOBAL_WEEK.sub().contains("只对这一条闹钟生效"));
        assertFalse(ScheduleSheet.Role.GLOBAL_ROTATION.sub().contains("只对这一条闹钟生效"));
    }

    /** 只有全局那张露时刻；只有轮班那两个露周期与起点。 */
    @Test
    public void roleFlagsMatchTheTwoDifferences() {
        assertTrue(ScheduleSheet.Role.GLOBAL_WEEK.withTimes);
        assertTrue(ScheduleSheet.Role.GLOBAL_ROTATION.withTimes);
        assertFalse(ScheduleSheet.Role.ALARM_WEEK.withTimes);
        assertFalse(ScheduleSheet.Role.ALARM_ROTATION.withTimes);

        assertFalse(ScheduleSheet.Role.GLOBAL_WEEK.rotation);
        assertTrue(ScheduleSheet.Role.GLOBAL_ROTATION.rotation);
        assertFalse(ScheduleSheet.Role.ALARM_WEEK.rotation);
        assertTrue(ScheduleSheet.Role.ALARM_ROTATION.rotation);
    }

    @Test
    public void titlesAreTheSameWordOnBothSheets() {
        assertEquals("自定义", ScheduleSheet.Role.GLOBAL_WEEK.title);
        assertEquals("自定义", ScheduleSheet.Role.ALARM_WEEK.title);
        assertEquals("轮班", ScheduleSheet.Role.GLOBAL_ROTATION.title);
        assertEquals("轮班", ScheduleSheet.Role.ALARM_ROTATION.title);
    }
}
