package io.github.iosephuschen.weekdayalarm;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 桌面小部件的法定假三角（v1.23.28）。
 *
 * <p>守的是「两处说同一句话」：主日历写「休 / 班 + 右上角三角」，小部件也得是。
 * 改之前小部件写「假」，和请假的「假」撞成一个字；轮班那边更是什么都看不出来。
 *
 * <p>判据必须是 {@code HolidayData.isHoliday}，不能走 {@code dayType}——
 * 轮班关掉「跳过法定节假日」时 {@code dayType} 永不返回 HOLIDAY，
 * 走它会得到「双休能看见、轮班看不见」这种半对的实现。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "xxhdpi")
public class WidgetHolidayDotTest {

    private static final int CELLS = 42;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private void doubleRest() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
    }

    /** 4 天一轮、上 2 休 2，与渲染验证用的是同一套设置。 */
    private void rotation() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setCycleStart(ctx(), LocalDate.of(2027, 2, 6));
        Prefs.setCycleHoliday(ctx(), false);
    }

    /** 某天在 42 格里的下标。 */
    private int indexOf(LocalDate base, LocalDate d) {
        YearMonth ym = YearMonth.from(base);
        int firstDow = ym.atDay(1).getDayOfWeek().getValue();
        int startDow = Prefs.isSundayFirst(ctx()) ? 7 : 1;
        int lead = (firstDow - startDow + 7) % 7;
        return lead + d.getDayOfMonth() - 1;
    }

    private MonthWidgetRender.Model oct2027() {
        doubleRest();
        return MonthWidgetRender.compute(ctx(), LocalDate.of(2027, 10, 1));
    }

    private MonthWidgetRender.Model feb2027() {
        rotation();
        return MonthWidgetRender.compute(ctx(), LocalDate.of(2027, 2, 1));
    }

    // ---------------------------------------------------------------- 数据层

    /** 双休：国庆七天全有三角，前后一天都没有。 */
    @Test
    public void doubleRestNationalDayHasDot() {
        MonthWidgetRender.Model m = oct2027();
        LocalDate base = LocalDate.of(2027, 10, 1);
        for (int day = 1; day <= 7; day++) {
            LocalDate d = base.withDayOfMonth(day);
            assertTrue("10 月 " + day + " 日该有三角", m.dot[indexOf(base, d)]);
        }
        assertFalse("9 月 30 日不该有三角", m.dot[indexOf(base, base.minusDays(1))]);
        assertFalse("10 月 8 日不该有三角", m.dot[indexOf(base, base.withDayOfMonth(8))]);
    }

    /**
     * 轮班：春节九天全有三角。
     *
     * <p>这条是这版的核心——以前轮班的格子里读不到任何「这是春节」的信息，
     * 因为 dayType 在轮班路径上根本不返回 HOLIDAY。
     */
    @Test
    public void rotationSpringFestivalHasDot() {
        MonthWidgetRender.Model m = feb2027();
        LocalDate base = LocalDate.of(2027, 2, 1);
        for (int day = 5; day <= 13; day++) {
            LocalDate d = base.withDayOfMonth(day);
            assertTrue("2 月 " + day + " 日该有三角", m.dot[indexOf(base, d)]);
        }
        assertFalse("2 月 4 日不该有三角", m.dot[indexOf(base, base.withDayOfMonth(4))]);
        assertFalse("2 月 14 日不该有三角", m.dot[indexOf(base, base.withDayOfMonth(14))]);
    }

    /** 补班日是「这天得上班」的命令，不是法定假，不能有三角。 */
    @Test
    public void makeupWorkdayHasNoDot() {
        MonthWidgetRender.Model m = oct2027();
        LocalDate base = LocalDate.of(2027, 10, 1);
        LocalDate makeup = base.withDayOfMonth(9);
        assertTrue("10 月 9 日该是补班", HolidayData.isMakeupWorkday(makeup));
        assertFalse("补班日不该有三角", m.dot[indexOf(base, makeup)]);
        assertEquals("补", m.tags[indexOf(base, makeup)]);
    }

    /** 没有节日名的法定假写「休」，和主日历一致；「假」只留给请假。 */
    @Test
    public void holidayWithoutNameWritesRest() {
        MonthWidgetRender.Model m = oct2027();
        LocalDate base = LocalDate.of(2027, 10, 1);
        // 10 月 2 日到 7 日在假期里，但没有自己的名字
        for (int day = 2; day <= 7; day++) {
            assertEquals("10 月 " + day + " 日该写「休」",
                    "休", m.tags[indexOf(base, base.withDayOfMonth(day))]);
        }
        // 有名字的保持原名，三角给不了名字
        assertEquals("国庆", m.tags[indexOf(base, base.withDayOfMonth(1))]);
    }

    /** 轮班那几天写的是班 / 休，不是「假」——三角是加在旁边的，不是替换。 */
    @Test
    public void rotationKeepsWorkRestTag() {
        MonthWidgetRender.Model m = feb2027();
        LocalDate base = LocalDate.of(2027, 2, 1);
        for (int day = 5; day <= 13; day++) {
            String tag = m.tags[indexOf(base, base.withDayOfMonth(day))];
            assertTrue("2 月 " + day + " 日写的是「" + tag + "」，既不该是「假」也不该是空的",
                    "班".equals(tag) || "休".equals(tag) || "除夕".equals(tag)
                            || "春节".equals(tag));
        }
    }

    /** 请假那天仍是「假」（黄字），并且如果它同时是法定假，三角照旧在。 */
    @Test
    public void leaveKeepsJiaAndStillHasDot() {
        doubleRest();
        LocalDate d = LocalDate.of(2027, 10, 3);
        Prefs.setDayShift(ctx(), d, WeekSchedule.DAY_LEAVE);
        MonthWidgetRender.Model m = MonthWidgetRender.compute(ctx(), LocalDate.of(2027, 10, 1));
        int i = indexOf(LocalDate.of(2027, 10, 1), d);
        assertEquals("假", m.tags[i]);
        assertEquals(0xFFFFD166, m.colors[i]);
        assertTrue("请假日碰上法定假，三角不能丢", m.dot[i]);
    }

    // ---------------------------------------------------------------- 渲染层

    /** 真实 RemoteViews 里，三角的可见性与 Model 完全一致。 */
    @Test
    public void remoteViewsVisibilityMatchesModel() throws Exception {
        MonthWidgetRender.Model m = oct2027();
        RemoteViews rv = MonthWidgetRender.build(ctx(), m);
        View root = rv.apply(ctx(), new FrameLayout(ctx()));

        int shown = 0;
        for (int i = 0; i < CELLS; i++) {
            View dot = root.findViewById(dotId(i));
            assertNotNull("布局里缺角标位 m_dot_" + i, dot);
            assertEquals("第 " + i + " 格三角可见性与 Model 不一致",
                    m.dot[i] ? View.VISIBLE : View.GONE, dot.getVisibility());
            if (m.dot[i]) shown++;
        }
        assertEquals("国庆七天该是七个三角", 7, shown);
    }

    /** 紧凑版也走同一套：42 个角标位一个都不能少。 */
    @Test
    public void compactLayoutHasAllDots() throws Exception {
        MonthWidgetRender.Model m = oct2027();
        RemoteViews rv = MonthWidgetRender.build(ctx(), m, R.layout.widget_month_compact, false);
        View root = rv.apply(ctx(), new FrameLayout(ctx()));
        for (int i = 0; i < CELLS; i++) {
            assertNotNull("紧凑版缺 m_dot_" + i, root.findViewById(dotId(i)));
        }
    }

    private static int dotId(int i) throws Exception {
        Field f = R.id.class.getField("m_dot_" + i);
        return f.getInt(null);
    }
}
