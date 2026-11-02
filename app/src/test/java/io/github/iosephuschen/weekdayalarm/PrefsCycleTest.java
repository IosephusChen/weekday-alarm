package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;

/**
 * 自定义 / 轮班的存盘往返（v1.1.0）。
 *
 * <p>这一层最容易出的不是逻辑错，是「存进去和读出来不是一个东西」：
 * 位掩码被截断、时间数组长度对不上、周期越界。任何一个都会让用户
 * 设好的班表在重启后变样，而他第二天早上才会发现。
 */
@RunWith(RobolectricTestRunner.class)
public class PrefsCycleTest {

    private Context c;

    @Before
    public void setUp() {
        c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void cycleRoundTrip() {
        LocalDate start = LocalDate.of(2026, 11, 2);
        Prefs.setWeekMode(c, WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(c, 4);
        Prefs.setCycleMask(c, 0b0011L);
        Prefs.setCycleStart(c, start);
        Prefs.setCycleTimes(c, new int[]{6 * 60, 14 * 60, -1, -1});
        Prefs.setCycleHoliday(c, false);

        assertEquals(WeekSchedule.Mode.CYCLE, Prefs.getWeekMode(c));
        assertEquals(4, Prefs.getCycleLen(c));
        assertEquals(0b0011L, Prefs.getCycleMask(c));
        assertEquals(start, Prefs.getCycleStart(c));
        assertArrayEquals(new int[]{6 * 60, 14 * 60, -1, -1}, Prefs.getCycleTimes(c));
        assertFalse(Prefs.isCycleHoliday(c));

        // getRule 是全局唯一出口，这里必须是同一份数据
        WeekSchedule.Rule r = Prefs.getRule(c);
        assertEquals(WeekSchedule.Mode.CYCLE, r.mode);
        assertEquals(4, r.cycleLen);
        assertEquals(6 * 60, WeekSchedule.cycleTimeOf(start, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(start.plusDays(2), r));
    }

    @Test
    public void oversizeLenClamped() {
        Prefs.setCycleLen(c, 200);
        assertEquals(WeekSchedule.Rule.MAX_CYCLE_LEN, Prefs.getCycleLen(c));

        Prefs.setCycleLen(c, 0);
        assertEquals(1, Prefs.getCycleLen(c));
    }

    @Test
    public void maskTrimmedToCurrentLen() {
        Prefs.setCycleLen(c, 3);
        Prefs.setCycleMask(c, 0xFFL);   // 8 天的位，周期只有 3 天
        // 读出来的高 5 位要被剪掉，否则「第 4 天」永远算不出休息
        assertEquals(0b111L, Prefs.getCycleMask(c));
    }

    @Test
    public void timesWithWrongLengthAreIgnored() {
        Prefs.setCycleLen(c, 4);
        Prefs.setCycleTimes(c, new int[]{360, 480});   // 只给了 2 个
        assertNull("长度对不上应当作没设过，而不是崩或者越界", Prefs.getCycleTimes(c));
    }

    @Test
    public void defaultHolidayFollowsKind() {
        // 自定义（勾星期）：服务业、教培春节真放假 → 默认跟
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_WEEK);
        assertTrue(Prefs.isCycleHoliday(c));

        // 轮班（几天一倒）：医院、工厂春节照上班 → 默认不跟
        c.getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_ROTATION);
        assertFalse(Prefs.isCycleHoliday(c));
    }

    @Test
    public void defaultMaskIsMonToFri() {
        // 从「自定义」入口点进来，看到的应该是一个能直接用的双休变体，
        // 而不是一张全灰的表还要自己从头点亮
        assertEquals(0b0011111L, Prefs.getCycleMask(c));
        assertEquals(7, Prefs.getCycleLen(c));
    }

    @Test
    public void otherModesUnaffected() {
        Prefs.setWeekMode(c, WeekSchedule.Mode.DOUBLE_REST);
        WeekSchedule.Rule r = Prefs.getRule(c);
        assertEquals(WeekSchedule.Mode.DOUBLE_REST, r.mode);
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(LocalDate.of(2026, 11, 4), r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(LocalDate.of(2026, 11, 7), r));
    }
}
