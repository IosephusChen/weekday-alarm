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
        assertTrue("按钮要说「撤销加班」", DayOffAction.isMarked(ctx(), SUNDAY));
        assertEquals("按钮文案", "今天撤销加班",
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
        assertEquals("明天撤销加班",
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
     * 真机报的问题（v1.23.12 装机后）：休息日点加班，按钮文案变了，<b>日历格子颜色不变</b>。
     *
     * <p>上面那条 {@code restDayOvertime_showsUpInButtonCalendarAndScheduler} 名字里
     * 写着 Calendar，实际一条格子断言都没有——只测了数据层、按钮和副标题。名字撒谎，
     * 于是这个洞一直开着。
     *
     * <p>这里直接读格子的底色：把「今天」设成休息日、标上加班，渲染后取那格的
     * {@link android.graphics.drawable.GradientDrawable} 实色，必须等于加班色。
     */
    @Test
    public void restDayOvertime_changesCalendarCellColor() throws Exception {
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
        Prefs.addOvertime(ctx(), today);

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        java.lang.reflect.Field panelField = MainActivity.class.getDeclaredField("calendarPanel");
        panelField.setAccessible(true);
        Object panel = panelField.get(a);
        java.lang.reflect.Field cellsField = panel.getClass().getDeclaredField("cells");
        cellsField.setAccessible(true);
        android.widget.LinearLayout[][] cells =
                (android.widget.LinearLayout[][]) cellsField.get(panel);

        LocalDate first = today.withDayOfMonth(1);
        LocalDate start = WeekSchedule.startOfWeek(first, Prefs.isSundayFirst(ctx()));
        long idx = java.time.temporal.ChronoUnit.DAYS.between(start, today);
        int r = (int) (idx / 7), c = (int) (idx % 7);

        android.graphics.drawable.Drawable bg = cells[r][c].getBackground();
        // 今天的格子是 bg_day_today（外层紫框 + 内层实底），底色在最里面那一层
        if (bg instanceof android.graphics.drawable.LayerDrawable) {
            bg = ((android.graphics.drawable.LayerDrawable) bg).getDrawable(1);
        }
        int actual = ((android.graphics.drawable.GradientDrawable) bg)
                .getColor().getDefaultColor();
        int expect = androidx.core.content.ContextCompat.getColor(ctx(), R.color.day_overtime_bg);

        assertEquals(String.format("第 %d 行第 %d 列不是今天的格子（期望加班底色 #%06X，实际 #%06X）",
                r, c, expect & 0xFFFFFF, actual & 0xFFFFFF), expect, actual);
    }

    /**
     * 同一处的另一半：今天上班、标了请假，格子也要变紫（而不是停在 today 的淡紫）。
     *
     * <p>不加这条，将来有人把「今天」的判断改成只看加班，请假会静默退回原样。
     */
    @Test
    public void workDayVacation_changesCalendarCellColor() throws Exception {
        sp().edit().clear().apply();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);

        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();
        long mask = 0b1111111L;                             // 七天全上班，今天才请得了假
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), mask);
        Prefs.setCycleStart(ctx(), today.minusDays(dow - 1));
        Prefs.addVacation(ctx(), today);

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        java.lang.reflect.Field panelField = MainActivity.class.getDeclaredField("calendarPanel");
        panelField.setAccessible(true);
        Object panel = panelField.get(a);
        java.lang.reflect.Field cellsField = panel.getClass().getDeclaredField("cells");
        cellsField.setAccessible(true);
        android.widget.LinearLayout[][] cells =
                (android.widget.LinearLayout[][]) cellsField.get(panel);

        LocalDate first = today.withDayOfMonth(1);
        LocalDate start = WeekSchedule.startOfWeek(first, Prefs.isSundayFirst(ctx()));
        long idx = java.time.temporal.ChronoUnit.DAYS.between(start, today);
        int r = (int) (idx / 7), c = (int) (idx % 7);

        android.graphics.drawable.Drawable bg = cells[r][c].getBackground();
        if (bg instanceof android.graphics.drawable.LayerDrawable) {
            bg = ((android.graphics.drawable.LayerDrawable) bg).getDrawable(1);
        }
        int actual = ((android.graphics.drawable.GradientDrawable) bg)
                .getColor().getDefaultColor();
        int expect = androidx.core.content.ContextCompat.getColor(ctx(), R.color.day_vacation_bg);

        assertEquals(String.format("今天请假，格子底色应是请假色（期望 #%06X，实际 #%06X）",
                expect & 0xFFFFFF, actual & 0xFFFFFF), expect, actual);
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
