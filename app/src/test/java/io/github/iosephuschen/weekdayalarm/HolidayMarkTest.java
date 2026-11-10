package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 法定节假日不再占底色，改为右上角三角（v1.23.27，方向二）。
 *
 * <p>这件事的起因是用户 2026-11-10 报的 bug：选了轮班制之后，首页日历上看不到
 * 春节——因为轮班默认「不跟法定节假日」（cycleHoliday=false，春节闹钟要照响），
 * 而节假日和补班都挂在 {@code dayType} 上，那个开关一关，格子里的节假日就跟着没了。
 *
 * <p>方向二把两件事拆开：底色回答「今天上不上班」，角标回答「今天是不是法定假」。
 * 所以这一组用例守的是三条线：
 *
 * <ul>
 *   <li>双休的法定假：底色必须是休息日那一片灰，不能再是整块金色；</li>
 *   <li>轮班的法定假：底色照旧是班 / 休，但角标必须在——这是本次要修的 bug；</li>
 *   <li>角标只属于"法定假"：普通休息日不许有，跨月的灰格不许有；
 *       请假 / 加班那天要照画，因为那两层说的事不一样。</li>
 * </ul>
 *
 * <p>不测三角的大小与颜色深浅：那属于观感，按 {@code AGENTS.md} 第六节等真机截图。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HolidayMarkTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    /** 建首页、把日历翻到目标月份再渲染一遍。 */
    private MainActivity openAt(LocalDate day) throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        CalendarPanel panel = panel(a);
        set(panel, "year", day.getYear());
        set(panel, "month", day.getMonthValue());
        panel.refresh();
        ShadowLooper.idleMainLooper();
        return a;
    }

    private CalendarPanel panel(MainActivity a) throws Exception {
        Field f = MainActivity.class.getDeclaredField("calendarPanel");
        f.setAccessible(true);
        return (CalendarPanel) f.get(a);
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 按日期取格子：6×7 骨架里第几行第几列由「每周第一天」推算，跟渲染同一套算法。 */
    private View cell(MainActivity a, LocalDate d) {
        LinearLayout rows = a.findViewById(R.id.cal_rows);
        LocalDate first = d.withDayOfMonth(1);
        LocalDate start = WeekSchedule.startOfWeek(first, Prefs.isSundayFirst(ctx()));
        int idx = (int) ChronoUnit.DAYS.between(start, d);
        assertTrue("日期必须落在 6×7 的网格里：" + d, idx >= 0 && idx < 42);
        return ((LinearLayout) rows.getChildAt(idx / 7)).getChildAt(idx % 7);
    }

    private String labelOf(View cell) {
        LinearLayout c = (LinearLayout) cell;
        return ((TextView) c.getChildAt(1)).getText().toString();
    }

    /** 格子的底色。今天那一格是 LayerDrawable，这些用例都取 2027 年的日期，碰不到。 */
    private int bgColor(View cell) {
        Drawable bg = cell.getBackground();
        assertTrue("格子背景该是 GradientDrawable，实际是 " + bg,
                bg instanceof GradientDrawable);
        ColorStateList cs = ((GradientDrawable) bg).getColor();
        assertNotNull("底色取不到（shape 里没有 solid？）", cs);
        return cs.getDefaultColor();
    }

    private int color(int res) {
        return ctx().getColor(res);
    }

    // ---------- 双休：法定假不再占底色 ----------

    @Test
    public void doubleRest_holidayWearsRestColorNotGold() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        View c = cell(a, LocalDate.of(2027, 10, 1));

        assertEquals("国庆的底色必须和休息日同一片灰，金色退场",
                color(R.color.day_rest_bg), bgColor(c));
    }

    @Test
    public void doubleRest_holidayHasMark() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        assertNotNull("国庆该有右上角三角", cell(a, LocalDate.of(2027, 10, 1)).getForeground());
    }

    @Test
    public void doubleRest_namedHolidayKeepsItsName() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        assertEquals("角标替不掉节日名：10/1 还得写「国庆」",
                "国庆", labelOf(cell(a, LocalDate.of(2027, 10, 1))));
    }

    @Test
    public void unnamedHoliday_saysRestInsteadOfJia() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        View c = cell(a, LocalDate.of(2027, 10, 4));

        assertEquals("没有节日名的法定假写「休」，「假」字不再上格子",
                WeekSchedule.DayType.REST.label, labelOf(c));
        assertNotNull("但它仍然是法定假，角标必须在", c.getForeground());
    }

    // ---------- 轮班：这才是用户报的那个 bug ----------

    @Test
    public void rotation_springFestivalWorkdayStillGetsMark() throws Exception {
        resetPrefs();
        // 4 天一轮、上二休二：2027-02-06 起算，2/7 正好是第二个上班日
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setCycleStart(ctx(), LocalDate.of(2027, 2, 6));
        Prefs.setCycleHoliday(ctx(), false);      // 轮班默认：春节照上班

        MainActivity a = openAt(LocalDate.of(2027, 2, 1));
        View c = cell(a, LocalDate.of(2027, 2, 7));

        assertEquals("轮班的底色必须说真话：这天上班",
                color(R.color.day_work_bg), bgColor(c));
        assertEquals("标签也得是「班」", WeekSchedule.DayType.WORK.label, labelOf(c));
        assertNotNull("但它落在春节里，角标必须在——这就是 2026-11-10 报的 bug",
                c.getForeground());
    }

    @Test
    public void rotation_restDayInHolidayAlsoGetsMark() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setCycleStart(ctx(), LocalDate.of(2027, 2, 6));
        Prefs.setCycleHoliday(ctx(), false);

        MainActivity a = openAt(LocalDate.of(2027, 2, 1));
        View c = cell(a, LocalDate.of(2027, 2, 8));   // 第三、四天，休

        assertEquals("休息日底色照旧", color(R.color.day_rest_bg), bgColor(c));
        assertNotNull("轮班的休息日落在春节里，也该有角标", c.getForeground());
    }

    // ---------- 角标只属于法定假 ----------

    @Test
    public void plainRestDay_hasNoMark() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        assertNull("10/10 是普通周日，不该有角标",
                cell(a, LocalDate.of(2027, 10, 10)).getForeground());
    }

    @Test
    public void plainWorkday_hasNoMark() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        assertNull("10/12 是普通周一，不该有角标",
                cell(a, LocalDate.of(2027, 10, 12)).getForeground());
    }

    // ---------- 两层各说各的：请假正好在节假日 ----------

    @Test
    public void leaveOnHoliday_keepsLeaveColorAndMark() throws Exception {
        resetPrefs();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        // 2027-10-02 在国庆假期里，同时标成请假
        Prefs.setDayPlan(ctx(), java.util.Collections.singletonMap(
                LocalDate.of(2027, 10, 2), WeekSchedule.DAY_LEAVE));

        MainActivity a = openAt(LocalDate.of(2027, 10, 1));
        View c = cell(a, LocalDate.of(2027, 10, 2));

        assertEquals("底色说「我请假了」", color(R.color.day_vacation_bg), bgColor(c));
        assertEquals("标签也还是请假", "请假", labelOf(c));
        assertNotNull("角标说「这天还是国庆」——两层不打架", c.getForeground());
    }
}
