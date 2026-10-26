package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 时刻 / 日期文案唯一出处（v0.73.7 收敛）的守门测试。
 *
 * <p>收敛前这些格式散在 8 个文件里各写一遍，这次收敛的价值全在「以后只有一处可改」。
 * 这几条钉子住三件事：补零规则（时刻补、月日不补）、星期表、解析失败返回 null
 * （PreNoticeReceiver 的「提前多久」靠它决定兜底值，不能抛）。
 */
@RunWith(RobolectricTestRunner.class)
public class TimeTextTest {

    @Test
    public void clock_padsHourAndMinute() {
        assertEquals("07:05", TimeText.clock(7, 5));
        assertEquals("23:30", TimeText.clock(23, 30));
        assertEquals("00:00", TimeText.clock(0, 0));
        assertEquals("09:38", TimeText.clock(LocalTime.of(9, 38)));
    }

    @Test
    public void monthDay_doesNotPad() {
        assertEquals("10月7日", TimeText.monthDay(LocalDate.of(2026, 10, 7)));
        assertEquals("1月1日", TimeText.monthDay(LocalDate.of(2026, 1, 1)));
    }

    @Test
    public void week_isChineseFullName() {
        assertEquals("周三", TimeText.week(LocalDate.of(2026, 10, 21)));
        assertEquals("周日", TimeText.week(LocalDate.of(2026, 10, 25)));
    }

    @Test
    public void monthDayWeek_joinsWithSpace() {
        assertEquals("10月21日 周三", TimeText.monthDayWeek(LocalDate.of(2026, 10, 21)));
    }

    @Test
    public void parseClock_returnsNullOnGarbage() {
        assertNull("坏输入返回 null，让调用方决定兜底，不在这里抛",
                TimeText.parseClock("not-a-time"));
        assertNull(TimeText.parseClock(""));
        assertNull(TimeText.parseClock(null));
        assertEquals(LocalTime.of(22, 30), TimeText.parseClock("22:30"));
    }
}
