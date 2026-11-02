package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 周休制度（v0.27.0）：双休 / 单休（周日）/ 单休（周六）/ 大小周。
 *
 * 这里守住三件事：
 * ① 三种新制度下周六周日的班休判断正确；
 * ② 大小周的老行为一点不变（老用户升级后日历、闹钟必须和升级前一模一样）；
 * ③ 法定节假日与调休补班永远压过制度——放假就是放假，补班就是补班。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WeekModeTest {

    /** 2026-10-24 周六、10-25 周日，两天都不在任何假期里。 */
    private static final LocalDate SAT = LocalDate.of(2026, 10, 24);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 25);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 23);

    private static WeekSchedule.Rule rule(WeekSchedule.Mode mode) {
        return new WeekSchedule.Rule(mode, SAT, false);
    }

    @Test
    public void doubleRestRestsBothDays() {
        WeekSchedule.Rule r = rule(WeekSchedule.Mode.DOUBLE_REST);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SUN, r));
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(FRI, r));
        assertFalse(WeekSchedule.isWorkday(SAT, r));
        assertFalse(WeekSchedule.isWorkday(SUN, r));
    }

    @Test
    public void singleRestSundayWorksOnSaturday() {
        WeekSchedule.Rule r = rule(WeekSchedule.Mode.SINGLE_REST_SUN);
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SUN, r));
        assertTrue(WeekSchedule.isWorkday(SAT, r));
        assertFalse(WeekSchedule.isWorkday(SUN, r));
    }

    @Test
    public void singleRestSaturdayWorksOnSunday() {
        WeekSchedule.Rule r = rule(WeekSchedule.Mode.SINGLE_REST_SAT);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(SUN, r));
        assertFalse(WeekSchedule.isWorkday(SAT, r));
        assertTrue(WeekSchedule.isWorkday(SUN, r));
    }

    @Test
    public void bigSmallBehaviourUnchanged() {
        // anchor = 大周周六；本周双休、下一周单休（休周日）
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.BIG_SMALL, SAT, false);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SUN, r));

        LocalDate nextSat = SAT.plusWeeks(1);
        LocalDate nextSun = SUN.plusWeeks(1);
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(nextSat, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(nextSun, r));
    }

    @Test
    public void bigSmallSmallRestSatStillReversed() {
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.BIG_SMALL, SAT, true);
        LocalDate nextSat = SAT.plusWeeks(1);
        LocalDate nextSun = SUN.plusWeeks(1);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(nextSat, r));
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(nextSun, r));
    }

    @Test
    public void holidayBeatsEveryMode() {
        LocalDate nationalDay = LocalDate.of(2026, 10, 2);   // 国庆假期，周五
        for (WeekSchedule.Mode m : WeekSchedule.Mode.values()) {
            // v1.1.0：CYCLE（自定义 / 轮班）**默认不跟节假日**——轮班的人春节照上班，
            // 这是刻意的设计，不是漏判。它的「跟 / 不跟」两种情形在 CycleScheduleTest 里单独覆盖。
            if (m.isCycle()) continue;
            assertEquals("制度 " + m + " 下 10/2 都应是假日",
                    WeekSchedule.DayType.HOLIDAY, WeekSchedule.dayType(nationalDay, rule(m)));
        }
    }

    @Test
    public void makeupWorkdayBeatsEveryMode() {
        LocalDate makeup = LocalDate.of(2026, 10, 10);       // 周六补班
        for (WeekSchedule.Mode m : WeekSchedule.Mode.values()) {
            if (m.isCycle()) continue;   // 同 holidayBeatsEveryMode：轮班默认不吃节假日与补班
            assertEquals("制度 " + m + " 下 10/10 都应是补班",
                    WeekSchedule.DayType.MAKEUP_WORK, WeekSchedule.dayType(makeup, rule(m)));
        }
    }

    @Test
    public void weekendAlarmFollowsMode() {
        // 「仅休息日」闹钟：单休（周日休）时只在周日响，周六要上班不该响
        Alarm a = new Alarm(1, 9, 0, "懒觉", Alarm.Mode.WEEKEND, true);
        assertFalse(a.appliesTo(SAT, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));
        assertTrue(a.appliesTo(SUN, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));

        // 单休（周六休）反过来
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.SINGLE_REST_SAT), false));
        assertFalse(a.appliesTo(SUN, rule(WeekSchedule.Mode.SINGLE_REST_SAT), false));

        // 双休两天都响
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.DOUBLE_REST), false));
        assertTrue(a.appliesTo(SUN, rule(WeekSchedule.Mode.DOUBLE_REST), false));
    }

    @Test
    public void workdayAlarmFollowsMode() {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        // 单休（周日休）：周六上班要响，周日不响
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));
        assertFalse(a.appliesTo(SUN, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));
        // 双休：周六日都不响
        assertFalse(a.appliesTo(SAT, rule(WeekSchedule.Mode.DOUBLE_REST), false));
        assertFalse(a.appliesTo(SUN, rule(WeekSchedule.Mode.DOUBLE_REST), false));
    }

    @Test
    public void overtimeStillWakesOnRestDay() {
        // 双休的周六标记加班 → 起床闹钟照响
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.DOUBLE_REST), true));
    }

    @Test
    public void describeText() {
        assertEquals("双休 · 休2天",
                WeekSchedule.describe(rule(WeekSchedule.Mode.DOUBLE_REST), SAT));
        assertEquals("单休 · 周日休",
                WeekSchedule.describe(rule(WeekSchedule.Mode.SINGLE_REST_SUN), SAT));
        assertEquals("单休 · 周六休",
                WeekSchedule.describe(rule(WeekSchedule.Mode.SINGLE_REST_SAT), SAT));
        // 大小周：anchor 当周是大周
        assertEquals("大周 · 休2天",
                WeekSchedule.describe(rule(WeekSchedule.Mode.BIG_SMALL), SAT));
    }

    /**
     * 周日的「本周」必须是刚过去的那个周六所在的周。
     * 旧实现按 ISO 周算，周日会跳到下周六：2026-10-25 是周日，日历这一行是
     * 10/19–10/25（周六 10/24 小周），标签却按 10/31 算成大周，两处对不上。
     */
    @Test
    public void sundayBelongsToTheWeekThatJustEnded() {
        assertEquals(SAT, WeekSchedule.saturdayOf(SUN));
        assertEquals(SAT, WeekSchedule.saturdayOf(SAT));
        assertEquals(SAT, WeekSchedule.saturdayOf(FRI));

        // 10/17 是大周周六 → 10/24 是小周 → 10/25 周日必须跟着这一周算小周
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.BIG_SMALL,
                SAT.minusWeeks(1), false);
        assertTrue(WeekSchedule.isBigWeekNow(SAT.minusWeeks(1), SAT.minusWeeks(1)));
        assertFalse(WeekSchedule.isBigWeekNow(SAT, SAT.minusWeeks(1)));
        assertFalse(WeekSchedule.isBigWeekNow(SUN, SAT.minusWeeks(1)));
        // 关键：周日与同一行的周六标签一致（旧实现这里会差一周）
        assertEquals(WeekSchedule.describe(r, SAT), WeekSchedule.describe(r, SUN));
        assertEquals("小周 · 休1天", WeekSchedule.describe(r, SUN));
    }
}
