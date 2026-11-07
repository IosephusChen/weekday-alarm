package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;

/**
 * 日历格子那两个字写什么（v0.91.8 抽出的共享方法）。
 *
 * <p><b>为什么补这组测试</b>：{@link DayTag#of} 是全项目唯一一处判断「法定节假日 &gt;
 * 农历节日 &gt; 节气 &gt; 基督教节日 &gt; 班/休/假/补」的地方，日历页与两个桌面小部件
 * 都调用它。抽出来之前，这段优先级在 {@link CalendarPanel} 和 {@link MonthWidgetRender}
 * 里各写一份，结果一个写「假」、一个写「休」——用户拿截图问「为什么 10/2 到 10/7 都是休」。
 * 抽成一个方法之后，没有任何用例钉住这个顺序：哪天有人觉得「节气应该比农历节日靠前」，
 * 改一行不会报错、不会崩，只会让日历和小部件一起错。
 *
 * <p>日期全部用内置数据覆盖的年份（2007–2026），不读系统时间、不依赖远程数据，
 * 结果与运行日期无关。
 */
public class DayTagTest {

    @Before
    public void setUp() {
        // 同一个 JVM 里别的用例可能合入过远程节假日；本组只验内置层，先清干净
        HolidayData.resetRemoteForTest();
    }

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    /**
     * 调休补班日的「补」不能被任何节日名顶掉。
     *
     * <p>2024-02-04 正好是「补班 + 立春」同一天：用户必须知道那天要上班，
     * 而节气只是顺带的信息。这是顺序里唯一一条「无条件优先」的规则。
     */
    @Test
    public void makeupWorkday_isNeverOverriddenByFestivalName() {
        LocalDate d = LocalDate.of(2024, 2, 4);
        assertTrue("2024-02-04 应为补班日", HolidayData.isMakeupWorkday(d));
        assertEquals("2024-02-04 同时是立春", "立春", SolarTerm.termOf(d));

        assertEquals("补班日的「补」比任何节日名都重要",
                "补", DayTag.of(d, WeekSchedule.DayType.MAKEUP_WORK, true, true, true));
    }

    /**
     * 农历节日优先于节气。
     *
     * <p>2026-03-20 同时是龙抬头与春分，正是这一层优先级的实测样本。
     */
    @Test
    public void lunarFestival_beatsSolarTerm() {
        LocalDate d = LocalDate.of(2026, 3, 20);
        assertEquals("前提：2026-03-20 是春分", "春分", SolarTerm.termOf(d));
        assertEquals("前提：2026-03-20 也是龙抬头", "龙抬头", LunarCalendar.festival(d));

        assertEquals("农历节日应排在节气之前",
                "龙抬头", DayTag.of(d, WeekSchedule.DayType.WORK, true, true, false));
    }

    /**
     * 法定假当天没有自己的简称时，退到农历节日；把农历开关关掉就退回「假」。
     *
     * <p>2026-02-16 是春节假期里的除夕：{@code isHoliday} 为 true，但名字表只标正日
     * （02-17 才是「春节」）。这正是「假」与节日名分工的那条路径。
     */
    @Test
    public void holidayWithoutOwnName_fallsThroughToLunar() {
        LocalDate d = LocalDate.of(2026, 2, 16);
        assertTrue("前提：2026-02-16 在春节假期内", HolidayData.isHoliday(d));
        assertNull("前提：名字只标正日，这天没有法定简称", HolidayData.holidayName(d));

        assertEquals("法定假没有简称时应退到农历节日",
                "除夕", DayTag.of(d, WeekSchedule.DayType.HOLIDAY, true, true, false));
        assertEquals("关掉农历节日就只剩「假」",
                "假", DayTag.of(d, WeekSchedule.DayType.HOLIDAY, false, true, false));
    }

    @Test
    public void lunarFestival_respectsToggle() {
        LocalDate d = LocalDate.of(2026, 8, 19);
        assertEquals("前提：2026-08-19 是七夕", "七夕", LunarCalendar.festival(d));

        assertEquals("开启时显示节日名",
                "七夕", DayTag.of(d, WeekSchedule.DayType.WORK, true, false, false));
        assertEquals("关闭时退回班/休",
                "班", DayTag.of(d, WeekSchedule.DayType.WORK, false, false, false));
    }

    @Test
    public void solarTerm_respectsToggle() {
        LocalDate d = LocalDate.of(2026, 2, 4);
        assertEquals("前提：2026-02-04 是立春", "立春", SolarTerm.termOf(d));

        assertEquals("开启时显示节气",
                "立春", DayTag.of(d, WeekSchedule.DayType.WORK, false, true, false));
        assertEquals("关闭时退回班/休",
                "班", DayTag.of(d, WeekSchedule.DayType.WORK, false, false, false));
    }

    @Test
    public void christianFestival_respectsToggle() {
        LocalDate d = LocalDate.of(2026, 12, 25);
        assertEquals("前提：2026-12-25 是圣诞", "圣诞", ChristianCalendar.festival(d));

        assertEquals("开启时显示节日名",
                "圣诞", DayTag.of(d, WeekSchedule.DayType.WORK, false, false, true));
        assertEquals("关闭时退回班/休",
                "班", DayTag.of(d, WeekSchedule.DayType.WORK, false, false, false));
    }

    /** 法定节日的简称优先级最高；没有节日时退回「班 / 休」。 */
    @Test
    public void legalHolidayName_isTopPriority_andPlainDayKeepsLabel() {
        assertEquals("国庆当天写「国庆」，不受另外三个开关影响",
                "国庆", DayTag.of(LocalDate.of(2026, 10, 1),
                        WeekSchedule.DayType.HOLIDAY, true, true, true));
        assertEquals("普通休息日写「休」",
                "休", DayTag.of(LocalDate.of(2026, 10, 20),
                        WeekSchedule.DayType.REST, false, false, false));
    }
}
