package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 「我的班表」整页（v1.3.0）。
 *
 * <p>它取代了原来的「周休制度」五选一弹窗。那些用例（大小周 anchor、单休休周六还是周日、
 * 卡片单选）没有删掉，只是换了入口重测一遍——这些行为本身仍然要守住，
 * 变的只是用户怎么表达「我是哪种班」：不再是选一张卡，而是点格子。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WeekScheduleActivityTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    /** 默认 Prefs 是大小周（老用户升级不能变），这一页的用例大多要一张七天表。 */
    private WeekScheduleActivity openDoubleRest() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        return open();
    }

    private WeekScheduleActivity open() {
        ActivityController<WeekScheduleActivity> c =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup();
        return c.get();
    }

    private static WeekGrid grid(WeekScheduleActivity a) {
        WeekGrid g = a.findViewById(R.id.week_grid);
        assertNotNull("格子组件应在", g);
        return g;
    }

    private static String text(WeekScheduleActivity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
    }

    private static void tap(WeekScheduleActivity a, int id) {
        a.findViewById(id).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static boolean checked(WeekScheduleActivity a, int id) {
        return ((SwitchCompat) a.findViewById(id)).isChecked();
    }

    /** 进来就该看得见自己那张表，而不是先做一道「你是哪种制度」的选择题。 */
    @Test
    public void opensWithVerdictAndSevenCells() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        WeekScheduleActivity a = open();

        assertNotNull("结论条应在", a.findViewById(R.id.tv_verdict));
        assertTrue("结论条应写出现在按哪张表排，实际：" + text(a, R.id.tv_verdict),
                text(a, R.id.tv_verdict).contains("双休"));
        assertEquals("默认只看一周", 7, grid(a).dayCount());
        assertEquals(View.GONE, a.findViewById(R.id.card_rotation).getVisibility());
    }

    /** 点周三成休：不像任何具名制度，落 7 天周期，并且入口算「按周」。 */
    @Test
    public void tappingMidweekCell_fallsBackToWeeklyCycle() {
        WeekScheduleActivity a = openDoubleRest();
        grid(a).cellAt(2).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(WeekSchedule.Mode.CYCLE, Prefs.getWeekMode(ctx()));
        assertEquals(7, Prefs.getCycleLen(ctx()));
        assertEquals("周一三五上班，周二周四也上班，周三休", 0b0011011L, Prefs.getCycleMask(ctx()));
        assertFalse("反推出来的表不能被当成轮班入口",
                Prefs.getCycleKind(ctx()) == Prefs.CYCLE_KIND_ROTATION);
    }

    /** 只把周日点成休 = 单休（休周六）；这正是原来「单休 · 休哪天」那个选项。 */
    @Test
    public void restingSundayOnly_makesSingleRestSaturday() {
        WeekScheduleActivity a = openDoubleRest();
        // 周六也点成上班 → 只休周日 = 单休（休周日）
        grid(a).cellAt(5).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
    }

    /** 勾「两周不一样」：格子长成十四格，第二周先复制第一周。 */
    @Test
    public void twoWeeks_switch_expandsGrid() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.sw_biweekly);

        assertEquals("勾上后长成两周", 14, grid(a).dayCount());
        assertTrue("开关应保持勾选", checked(a, R.id.sw_biweekly));
    }

    /** 第二周周六也上班 = 标准大小周，anchor 落在本周六（与旧弹窗同一套算法）。 */
    @Test
    public void secondWeekDifferent_makesBigSmallWithAnchor() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.sw_biweekly);
        grid(a).cellAt(12).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx()));
        assertEquals("anchor 是本周期那个周六",
                WeekSchedule.saturdayOf(LocalDate.now()), Prefs.getAnchor(ctx()));
    }

    /** 「法定节假日放假」关掉：轮班、厂里春节照上班的人要用。 */
    @Test
    public void holidaySwitch_writesThrough() {
        WeekScheduleActivity a = openDoubleRest();
        assertTrue("默认跟节假日", checked(a, R.id.sw_holiday));

        tap(a, R.id.sw_holiday);
        assertFalse(Prefs.isCycleHoliday(ctx()));
        assertTrue("副标题要跟着改成「照常上班」，实际：" + text(a, R.id.tv_holiday_sub),
                text(a, R.id.tv_holiday_sub).contains("照常上班"));
    }

    /** 已经是 N 天一轮：七格表达不了，换成摘要卡 + 「改成按周」。 */
    @Test
    public void rotationMode_showsSummaryCard() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setCycleStart(ctx(), LocalDate.now());
        WeekScheduleActivity a = open();

        assertEquals(View.VISIBLE, a.findViewById(R.id.card_rotation).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.row_back_week).getVisibility());
        assertEquals(View.GONE, grid(a).getVisibility());
        assertTrue("摘要要写出周期，实际：" + text(a, R.id.tv_rotation_title),
                text(a, R.id.tv_rotation_title).contains("4 天一轮"));
    }

    /** 「改成按周」：从 N 天一轮回到七格，且不再被判定成轮班。 */
    @Test
    public void backToWeek_leavesRotation() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setCycleStart(ctx(), LocalDate.now());
        WeekScheduleActivity a = open();

        tap(a, R.id.row_back_week);

        assertFalse("不该再是轮班入口",
                Prefs.getCycleKind(ctx()) == Prefs.CYCLE_KIND_ROTATION);
        assertEquals(View.VISIBLE, grid(a).getVisibility());
    }

    /** 结论条随格子变：改完不用退出去也能确认系统认成了什么。 */
    @Test
    public void verdict_followsTheGrid() {
        WeekScheduleActivity a = openDoubleRest();
        String before = text(a, R.id.tv_verdict);

        // 周六也上班 → 只休周日 → 单休
        grid(a).cellAt(5).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("改完结论条应变成单休，实际：" + text(a, R.id.tv_verdict),
                text(a, R.id.tv_verdict).contains("单休"));
        assertTrue("不该还写着改之前那句", !before.equals(text(a, R.id.tv_verdict)));
    }

    /** 周一必须是同一个 ISO 周的周一：周日进来不能跳到下周一。 */
    @Test
    public void mondayOfWeek_isSameWeek() {
        LocalDate mon = WeekScheduleActivity.mondayOfWeek();
        assertEquals(DayOfWeek.MONDAY, mon.getDayOfWeek());
        assertEquals(0, Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                mon, LocalDate.now())));
        assertTrue("今天必须落在本周一到周日之间",
                !LocalDate.now().isBefore(mon) && LocalDate.now().isBefore(mon.plusDays(7)));
    }
}
