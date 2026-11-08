package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 切回按周后残留的班次表（v1.10.0）。
 *
 * <p>bug 是这么来的：轮班里排过表、闹钟勾了班次，然后切回双休 / 单休。
 * {@code shift_cycle} 没人清（切回轮班还要用），但 {@link WeekSchedule#shiftOn}
 * 在 v1.8.1 加了「不是 CYCLE 就返回 null」的闸门。于是
 * {@link Alarm#appliesTo} 里那个 {@code s == null → return overtime} 把
 * 周一到周五全部判成「今天不响」——闹钟好好的，首页却写
 * 「今天上班，但没有要响的闹钟」，倒计时条整条收起（v1.23.10 起；此前是 --:--:--）。
 *
 * <p>这里钉住三件事：判定、时刻、标记，三处必须都认为「这条闹钟与班次无关」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class StaleShiftCycleTest {

    /** 2026-11-04 是周三，单休（周日休）制度下要上班。 */
    private static final LocalDate WED = LocalDate.of(2026, 11, 4);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static List<Shift> shifts() {
        List<Shift> list = new ArrayList<>();
        list.add(new Shift(1, "白班", 8 * 60, 17 * 60, 0));
        return list;
    }

    /** 单休 + 残留的 4 天班次表（白 / 白 / 休 / 休）。 */
    private static WeekSchedule.Rule staleRule() {
        return new WeekSchedule.Rule(WeekSchedule.Mode.SINGLE_REST_SUN, null, false,
                4, 0b0011L, LocalDate.of(2026, 11, 2), null, false,
                new int[]{1, 1, -1, -1},
                shifts().toArray(new Shift[0]));
    }

    /** 06:30 起床，只勾了白班——切回单休前留下的那条。 */
    private static Alarm staleAlarm() {
        Alarm a = new Alarm(1, 6, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.shiftIds = new int[]{1};
        a.offsetMin = -90;
        return a;
    }

    @Test
    public void appliesTo_worksOnAWorkday() {
        assertTrue("切回单休后，周三照样要响",
                staleAlarm().appliesTo(WED, staleRule(), false));
    }

    @Test
    public void timeOn_fallsBackToOwnClock() {
        assertEquals(6 * 60 + 30, staleAlarm().timeOn(WED, staleRule(), false));
    }

    @Test
    public void followsShifts_isFalse() {
        assertFalse("班表已经不是轮班，就不该再挂「跟班表」",
                staleAlarm().followsShifts(staleRule()));
    }

    @Test
    public void nextRing_findsTomorrowMorning() {
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(
                Collections.singletonList(staleAlarm()), staleRule(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                LocalDateTime.of(2026, 11, 3, 15, 0), 90);

        assertNotNull("首页倒计时不该是 --:--:--", next);
        assertEquals(LocalDateTime.of(2026, 11, 4, 6, 30), next.when);
    }
}
