package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 七格班表模型（v0.24.0 引入，v0.71.0 随 4×2 本周班表组件删除而改测共享渲染类）。
 *
 * <p>原来这个文件测的是 {@code WeekScheduleWidget}。那个组件删了，但 7 天的判定还在用
 * （4×2 闹钟卡的七格、4×4 与 4×3 的按钮），逻辑搬到了 {@link WeekWidgetRender}。
 * 真正会错的仍是这 7 天：必须和 App 内日历同源，所以逐条核对星期顺序、今天高亮、
 * 请假/加班覆盖，以及大周/小周标题。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WeekWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
    }

    @Before
    public void setUp() {
        sp().edit().clear().commit();
    }

    @Test
    public void model_isMondayToSundayWithTodayFlagged() {
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        assertEquals(7, m.days.size());
        String[] letters = {"一", "二", "三", "四", "五", "六", "日"};
        int todayIndex = LocalDate.now().getDayOfWeek().getValue() - 1;
        int flagged = 0;
        for (int i = 0; i < 7; i++) {
            assertEquals(letters[i], m.days.get(i).dayLetter);
            if (m.days.get(i).today) {
                flagged++;
                assertEquals("今天应该落在正确的星期列", todayIndex, i);
            }
        }
        assertEquals("有且只有一天是今天", 1, flagged);
    }

    @Test
    public void model_labelsCoverAllDayTypes() {
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        for (WeekWidgetRender.DayCell c : m.days) {
            assertNotNull(c.label);
            assertFalse("标签不能为空，否则格子里会缺字", c.label.isEmpty());
            assertTrue("标签最多 2 字（格子宽度只够 2 字）",
                    c.label.equals("班") || c.label.equals("休") || c.label.equals("假")
                            || c.label.equals("补") || c.label.equals("请假")
                            || c.label.equals("加班"));
        }
    }

    @Test
    public void model_titleMatchesBigOrSmallWeek() {
        WeekWidgetRender.WeekModel m = WeekWidgetRender.computeModel(ctx());
        boolean big = WeekSchedule.isBigWeekNow(LocalDate.now(), Prefs.getAnchor(ctx()));
        // v0.27.1：标题从「本周 · 大周（休2天）」精简为「大周 · 休2天」，格子窄，前缀留给制度
        assertTrue(big ? m.title.startsWith("大周") : m.title.startsWith("小周"));
        assertTrue(m.title.contains("休"));
    }

    @Test
    public void model_subtitleSaysAlarmsOn() {
        assertTrue(WeekWidgetRender.computeModel(ctx()).subtitle.contains("已开启"));
    }

    /** 本周内找一个上班日 / 休息日（一周里两者必然都有）。 */
    private LocalDate findInThisWeek(boolean wantWork) {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            boolean work = WeekSchedule.isWorkday(d, Prefs.getAnchor(ctx()),
                    Prefs.isSmallRestSat(ctx()));
            if (work == wantWork) return d;
        }
        throw new IllegalStateException("本周找不到目标类型的一天");
    }

    private WeekWidgetRender.DayCell cellOf(WeekWidgetRender.WeekModel m, LocalDate d) {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        int idx = (int) java.time.temporal.ChronoUnit.DAYS.between(monday, d);
        return m.days.get(idx);
    }

    @Test
    public void model_vacationOverridesWorkdayLabel() {
        LocalDate workday = findInThisWeek(true);
        Prefs.addVacation(ctx(), workday);
        WeekWidgetRender.DayCell c = cellOf(WeekWidgetRender.computeModel(ctx()), workday);
        assertTrue(c.vacation);
        assertEquals("请假", c.label);
    }

    @Test
    public void model_overtimeOverridesRestDayLabel() {
        LocalDate rest = findInThisWeek(false);
        Prefs.addOvertime(ctx(), rest);
        WeekWidgetRender.DayCell c = cellOf(WeekWidgetRender.computeModel(ctx()), rest);
        assertTrue(c.overtime);
        assertEquals("加班", c.label);
    }

    @Test
    public void model_holidayLabelForNationalDay() {
        // 2026-10-01 是法定节假日（国庆），落在哪一周都该显示「假」
        LocalDate d = LocalDate.of(2026, 10, 1);
        WeekSchedule.DayType t = WeekSchedule.dayType(d, Prefs.getAnchor(ctx()), false);
        assertEquals(WeekSchedule.DayType.HOLIDAY, t);
        assertEquals("假", t.label);
    }

    @Test
    public void updateEntryPoints_areNullSafe() {
        NextAlarmWidget w = new NextAlarmWidget();
        w.onUpdate(ctx(), null, new int[]{});
        w.onUpdate(ctx(), android.appwidget.AppWidgetManager.getInstance(ctx()), new int[]{});
        NextAlarmWidget.updateAll(ctx());
        Widgets.updateAll(ctx());   // 三个组件一起刷（无实例时也必须是空操作）
    }
}
