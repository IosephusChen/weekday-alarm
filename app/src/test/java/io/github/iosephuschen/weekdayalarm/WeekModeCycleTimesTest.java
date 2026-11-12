package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * B4：切回双休/单休后，残留的逐日时刻不能再生效。
 *
 * <p>自定义 7 天设过 06:00，再改成双休；「仅上班日」闹钟自身是 07:30，
 * 但 {@code WeekSchedule.cycleTimeOf} 不看 mode，仍返回 06:00——编辑页显示
 * 07:30、实际 06:00 响，且没有「跟班表」标记。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WeekModeCycleTimesTest {

    /** 2026-11-09 是周一。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Alarm weekdayAlarm() {
        return new Alarm(21, 7, 30, "上班", Alarm.Mode.WORKDAY, true);
    }

    @Test
    public void doubleRest_ignoresLeftoverCycleTimes() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), 0b0011111);           // 周一至周五
        Prefs.setCycleTimes(ctx(), new int[]{360, 360, 360, 360, 360, -1, -1});

        assertEquals("残留的 06:00 不该盖掉闹钟自己的 07:30",
                7 * 60 + 30, weekdayAlarm().timeOn(MONDAY, Prefs.getRule(ctx()), false));
    }

    @Test
    public void switchingBackToCycle_restoresTheKeptData() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), 0b0011111);
        Prefs.setCycleTimes(ctx(), new int[]{360, 360, 360, 360, 360, -1, -1});
        assertEquals(7 * 60 + 30, weekdayAlarm().timeOn(MONDAY, Prefs.getRule(ctx()), false));

        // 数据没被删，切回自定义就恢复——这是「不清理用户数据」的约定
        assertNotNull(Prefs.getCycleTimes(ctx()));
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        assertEquals("切回自定义后逐日时刻应恢复生效",
                360, weekdayAlarm().timeOn(MONDAY, Prefs.getRule(ctx()), false));
    }
}
