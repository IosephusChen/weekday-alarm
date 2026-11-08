package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 「自定义七天 + 休息日点加班」必须真的生效（v1.23.12）。
 *
 * <p>用户实测：排班选自定义、周日和周一休息，今天周日（2026-11-08），点「今天要加班」，
 * 副标题从「今日休息」变成「今日加班」，按钮文案和日历格子却一动不动；点「明天要加班」
 * 同样。原因是写入端按「模式是 CYCLE」判定有班次，存了正数班次 id；读取端按
 * 「这张班表真的排了班次」判定它是残影，两边对同一份数据各说一套。
 *
 * <p>这条测试不用 {@code LocalDate.now()}：日期一固定，「今天」到底是周几就由测试说了算，
 * 不会出现「周一跑绿、周日跑红」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CustomWeekOvertimeTest {

    /** 周二到周六上班，周日周一休。 */
    private static final long MASK = 0b0011110L;
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 8);
    private static final LocalDate MONDAY = SUNDAY.plusDays(1);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    private void customWeekWithLeftoverShift() {
        sp().edit().clear().apply();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), MASK);
        Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));   // 周一
        // 关键前提：班次定义表里有一个白班，但这张班表没排它（shiftCycle 为 null）。
        // 用户「以前建过班次」就是这个状态，也正是旧代码误判的来源。
        sp().edit().putString(Prefs.KEY_SHIFT_DEFS,
                Shift.toJson(Arrays.asList(
                        new Shift(1, "白班", 8 * 60, Shift.END_UNKNOWN, 0)))).apply();
    }

    @Test
    public void restDayOvertime_isStoredAsUnspecified_notShiftId() {
        customWeekWithLeftoverShift();

        Prefs.addOvertime(ctx(), SUNDAY);

        assertEquals("这张班表没有班次，不能存正数 id",
                Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY), Prefs.getDayShift(ctx(), SUNDAY));
    }

    @Test
    public void restDayOvertime_showsUpInButtonCalendarAndScheduler() {
        customWeekWithLeftoverShift();
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        WeekSchedule.DayType sundayType = WeekSchedule.dayType(SUNDAY, rule.withDays(null));

        Prefs.addOvertime(ctx(), SUNDAY);

        assertTrue("数据层认这条加班", Prefs.isOvertime(ctx(), SUNDAY));
        assertTrue("排程集合要含这天，否则闹钟不响",
                Prefs.getOvertimeDates(ctx()).contains(SUNDAY));
        assertTrue("按钮要说「已加班」", DayOffAction.isMarked(ctx(), SUNDAY));
        assertEquals("按钮文案", "今天已加班",
                DayOffAction.buttonText(DayOffAction.TODAY, sundayType, true));
        assertEquals("副标题与大字", "今天加班",
                TodayCard.statusText(false, true, DayOffAction.isWorkDay(sundayType), null));
    }

    @Test
    public void tomorrowRestDayOvertime_alsoTakesEffect() {
        customWeekWithLeftoverShift();
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        WeekSchedule.DayType mondayType = WeekSchedule.dayType(MONDAY, rule.withDays(null));

        Prefs.addOvertime(ctx(), MONDAY);

        assertEquals(Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY),
                Prefs.getDayShift(ctx(), MONDAY));
        assertTrue(Prefs.isOvertime(ctx(), MONDAY));
        assertTrue(DayOffAction.isMarked(ctx(), MONDAY));
        assertEquals("明天已加班",
                DayOffAction.buttonText(DayOffAction.TOMORROW, mondayType, true));
        assertFalse("今天不该被顺手标上", Prefs.isOvertime(ctx(), SUNDAY));
    }

    @Test
    public void toggleUndoesTheOvertime() {
        customWeekWithLeftoverShift();
        DayOffAction.toggle(ctx(), SUNDAY);
        assertTrue("先点一下应标上", DayOffAction.isMarked(ctx(), SUNDAY));

        assertFalse("再点一下应撤销", DayOffAction.toggle(ctx(), SUNDAY));
        assertFalse(Prefs.isOvertime(ctx(), SUNDAY));
        assertFalse(DayOffAction.isMarked(ctx(), SUNDAY));
    }

    /**
     * 首页副标题也不能被残影骗。这一处原来是全 App 最宽的：只判「有没有值」，
     * 连 {@code hasShifts()} 都不看，所以只有它说「今日加班」。
     *
     * <p>用 {@code LocalDate.now()} 是因为首页自己就用它——把「今天」在班表里设成
     * 休息日即可，不依赖跑测试那天是周几。
     */
    @Test
    public void leftoverShiftId_doesNotShowAsOvertimeOnHome() {
        sp().edit().clear().apply();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);

        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();          // 1 = 周一
        long mask = 0b1111111L & ~(1L << (dow - 1));        // 今天休息，其余上班
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), mask);
        Prefs.setCycleStart(ctx(), today.minusDays(dow - 1));
        sp().edit().putString(Prefs.KEY_SHIFT_DEFS, Shift.toJson(Arrays.asList(
                new Shift(1, "白班", 8 * 60, Shift.END_UNKNOWN, 0)))).apply();
        Prefs.setDayShift(ctx(), today, 1);                 // 残影：正数 id，但没排班次

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView status = a.findViewById(R.id.tv_today_status);
        assertNotNull(status);
        assertEquals("残影不该在首页说「今天加班」", "今天休息", status.getText().toString());
    }
}
