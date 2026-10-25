package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 「今天状态卡」与 4×2 桌面小组件共用的文案规则（v1.69.0 从 MainActivity 抽出）。
 *
 * <p>这些字符串同时出现在首页卡片和桌面小组件上——同一天两处必须逐字一致，
 * 否则用户只会更疑心哪个错了。规则本身从 v1.22.5 / v1.62.0 起就没变过，
 * 这条测试把既有行为原样钉死，防的是抽取过程改丢字。
 */
public class TodayCardTest {

    private static final LocalDate today = LocalDate.of(2026, 10, 25); // 周日，固定日期

    // ---------- 大字段 ----------

    @Test
    public void statusText_fourStates() {
        assertEquals("今天请假", TodayCard.statusText(true, false, false));
        assertEquals("今天加班", TodayCard.statusText(false, true, true));
        assertEquals("今天上班", TodayCard.statusText(false, false, true));
        assertEquals("今天休息", TodayCard.statusText(false, false, false));
    }

    // ---------- 角标 ----------

    @Test
    public void chipText_comesFromWeekScheduleDescribe() {
        // 角标就是 WeekSchedule.describe 的薄包装，首页 chip 与桌面 chip 同源
        assertEquals(WeekSchedule.describe(PrefsRuleFixture.doubleRest(), today),
                TodayCard.chipText(PrefsRuleFixture.doubleRest(), today));
    }

    // ---------- 副信息前缀 ----------

    @Test
    public void dayTagText_priorityAndEmptyForPlainWorkday() {
        assertEquals("个人请假", TodayCard.dayTagText(WeekSchedule.DayType.WORK, true, false, true));
        assertEquals("个人加班", TodayCard.dayTagText(WeekSchedule.DayType.REST, false, true, false));
        assertEquals("调休补班", TodayCard.dayTagText(WeekSchedule.DayType.MAKEUP_WORK, false, false, true));
        assertEquals("法定节假日", TodayCard.dayTagText(WeekSchedule.DayType.HOLIDAY, false, false, false));
        assertEquals("休息日", TodayCard.dayTagText(WeekSchedule.DayType.REST, false, false, false));
        assertEquals("普通上班日不出前缀", "", TodayCard.dayTagText(WeekSchedule.DayType.WORK, false, false, true));
    }

    // ---------- 响铃时刻 ----------

    @Test
    public void ringText_relativeWordsThenDate() {
        assertEquals("今天只写时分", "07:00",
                TodayCard.ringText(at(0, 7, 0), today));
        assertEquals("明天加前缀", "明天 07:00",
                TodayCard.ringText(at(1, 7, 0), today));
        assertEquals("后天加前缀", "后天 07:00",
                TodayCard.ringText(at(2, 7, 0), today));
        assertEquals("更远补日期星期", "10月30日 周五 07:00",
                TodayCard.ringText(at(5, 7, 0), today));
    }

    @Test
    public void ringText_labelAppendedWhenPresent() {
        assertEquals("明天 07:00 起床",
                TodayCard.ringText(new ScheduleManager.NextAlarm(dt(1, 7, 0), "起床", 1), today));
        assertEquals("没有名字不补空格", "明天 07:00",
                TodayCard.ringText(new ScheduleManager.NextAlarm(dt(1, 7, 0), "", 1), today));
        assertEquals("null 名字不补空格", "明天 07:00",
                TodayCard.ringText(new ScheduleManager.NextAlarm(dt(1, 7, 0), null, 1), today));
    }

    // ---------- 副信息整行 ----------

    @Test
    public void subText_vacationOverridesEverything() {
        // 请假当天一律「个人请假 · 闹钟全部跳过」，哪怕有闹钟、是上班日
        assertEquals("个人请假 · 闹钟全部跳过",
                TodayCard.subText("个人请假", at(0, 7, 0), today, true, true));
    }

    @Test
    public void subText_noAlarmExplainsWhy() {
        assertEquals("上班日说清楚今天没有要响的",
                "今天上班，但没有要响的闹钟",
                TodayCard.subText("", null, today, true, false));
        assertEquals("休息日只说没有启用的闹钟",
                "没有启用的闹钟",
                TodayCard.subText("休息日", null, today, false, false));
    }

    @Test
    public void subText_prefixAndNextVsFuture() {
        assertEquals("当天用「下一个」，无前缀时直接接",
                "下一个 23:59",
                TodayCard.subText("", at(0, 23, 59), today, true, false));
        assertEquals("非当天用「下次」，前缀用「 · 」连接",
                "休息日 · 下次 明天 07:00",
                TodayCard.subText("休息日", at(1, 7, 0), today, false, false));
        assertEquals("调休补班日也带前缀",
                "调休补班 · 下次 明天 07:00",
                TodayCard.subText("调休补班", at(1, 7, 0), today, true, false));
    }

    // ---------- 工具 ----------

    private static LocalDateTime dt(int dayOffset, int hour, int minute) {
        return today.plusDays(dayOffset).atTime(hour, minute);
    }

    /** 无标签闹钟——ringText 的相对词用例要的就是「今天只写时分」的干净输出。 */
    private static ScheduleManager.NextAlarm at(int dayOffset, int hour, int minute) {
        return new ScheduleManager.NextAlarm(dt(dayOffset, hour, minute), null, 1);
    }

    /** 双休规则。只为 chipText 用例服务，anchor 取值不影响结论。 */
    static final class PrefsRuleFixture {
        static WeekSchedule.Rule doubleRest() {
            return new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, today, false);
        }
    }
}
