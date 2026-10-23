package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 今日卡的「今天 / 明天不上班」（v1.28.1 起，v1.34.0 起并排两个）。
 *
 * <p>加这两个按钮的理由很实际：标一天假原本要「滚到日历 → 找到那天 → 点 → 确认」四步，
 * 而「今天临时有事」「明早要请假」恰恰是最急的两种。现在今日卡里点一下就完事，再点一下撤销。
 *
 * <p>这里钉五件事：文案按班休和今天/明天切换、两个按钮各改各的日子、点完能撤销、
 * 别处标记不受影响、以及日历确实回到了首页（不再是跳转页）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DayOffButtonTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private MainActivity launch() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    // ---------- 纯函数：文案 ----------

    @Test
    public void text_onWorkDay_offersDayOff() {
        assertEquals("今天不上班",
                MainActivity.dayOffButtonText(DayOffAction.TODAY, WeekSchedule.DayType.WORK, false));
        assertEquals("标记后改说「已请假」",
                "今天已请假",
                MainActivity.dayOffButtonText(DayOffAction.TODAY, WeekSchedule.DayType.WORK, true));
    }

    @Test
    public void text_tomorrow_usesTomorrowPrefix() {
        assertEquals("明天不上班",
                MainActivity.dayOffButtonText(DayOffAction.TOMORROW,
                        WeekSchedule.DayType.WORK, false));
        assertEquals("明天已加班",
                MainActivity.dayOffButtonText(DayOffAction.TOMORROW,
                        WeekSchedule.DayType.REST, true));
    }

    @Test
    public void text_onMakeupWorkDay_alsoOffersDayOff() {
        // 调休补班日也是上班日：用户那天照样想请假，按钮不该变成「今天要加班」
        assertEquals("今天不上班",
                MainActivity.dayOffButtonText(DayOffAction.TODAY,
                        WeekSchedule.DayType.MAKEUP_WORK, false));
    }

    @Test
    public void text_onRestDay_offersOvertime() {
        assertEquals("今天要加班",
                MainActivity.dayOffButtonText(DayOffAction.TODAY, WeekSchedule.DayType.REST, false));
        assertEquals("今天已加班",
                MainActivity.dayOffButtonText(DayOffAction.TODAY, WeekSchedule.DayType.REST, true));
    }

    @Test
    public void text_onLegalHoliday_offersOvertime() {
        // 法定假按休息处理：那天想上班才是特例
        assertEquals("今天要加班",
                MainActivity.dayOffButtonText(DayOffAction.TODAY,
                        WeekSchedule.DayType.HOLIDAY, false));
    }

    @Test
    public void isWorkDayType_coversWorkAndMakeupOnly() {
        assertTrue(MainActivity.isWorkDayType(WeekSchedule.DayType.WORK));
        assertTrue(MainActivity.isWorkDayType(WeekSchedule.DayType.MAKEUP_WORK));
        assertFalse(MainActivity.isWorkDayType(WeekSchedule.DayType.REST));
        assertFalse(MainActivity.isWorkDayType(WeekSchedule.DayType.HOLIDAY));
    }

    /** 脏 offset（小组件旧 PendingIntent 里可能烧着 5）一律按明天，不会去标别的日期。 */
    @Test
    public void clampOffset_onlyTodayAndTomorrow() {
        assertEquals(DayOffAction.TODAY, DayOffAction.clampOffset(0));
        assertEquals(DayOffAction.TOMORROW, DayOffAction.clampOffset(1));
        assertEquals(DayOffAction.TOMORROW, DayOffAction.clampOffset(5));
        assertEquals(DayOffAction.TOMORROW, DayOffAction.clampOffset(-3));
    }

    // ---------- 真按钮：点一下、再点一下 ----------

    /**
     * 点一下标记、再点一下撤销，两个方向都要能来回。
     *
     * <p>不写死「今天是上班日」——跑测试那天是星期几由不得我们。按当天真实类型
     * 决定该看请假集合还是加班集合，这样周一到周日跑都是同一条断言。
     */
    @Test
    public void tappingButton_togglesTodayMark() {
        resetPrefs();
        MainActivity a = launch();
        TextView btn = a.findViewById(R.id.btn_day_off);
        assertNotNull("今日卡里应有「今天不上班」按钮", btn);

        LocalDate today = LocalDate.now();
        boolean work = MainActivity.isWorkDayType(
                WeekSchedule.dayType(today, Prefs.getRule(ctx())));

        btn.performClick();
        if (work) {
            assertTrue("上班日点一下应标成请假", Prefs.isVacation(ctx(), today));
            assertTrue("标记后文案应说「已请假」", btn.getText().toString().contains("已请假"));
        } else {
            assertTrue("休息日点一下应标成加班", Prefs.isOvertime(ctx(), today));
            assertTrue("标记后文案应说「已加班」", btn.getText().toString().contains("已加班"));
        }
        assertTrue("标记后按钮应处于 selected 态", btn.isSelected());

        btn.performClick();
        if (work) {
            assertFalse("再点一下应撤销请假", Prefs.isVacation(ctx(), today));
            assertTrue("撤销后文案应回到「不上班」", btn.getText().toString().endsWith("不上班"));
        } else {
            assertFalse("再点一下应撤销加班", Prefs.isOvertime(ctx(), today));
            assertTrue("撤销后文案应回到「要加班」", btn.getText().toString().endsWith("要加班"));
        }
        assertFalse("撤销后不该还是 selected 态", btn.isSelected());
    }

    /**
     * 标记后背景要换（v1.34.1）。
     *
     * <p>只改文案不够：原来「今天不上班 ✓」压在紫色渐变上，✓ 太小，用户点完不确定生效没有。
     * 现在跟桌面小组件一样换成实白底 + 紫字，一整个按钮亮起来，比一个符号清楚得多。
     */
    @Test
    public void markingButton_switchesBackgroundAndTextColor() {
        resetPrefs();
        MainActivity a = launch();
        TextView btn = a.findViewById(R.id.btn_day_off);
        int white = ContextCompat.getColor(ctx(), R.color.white);
        int heroText = ContextCompat.getColor(ctx(), R.color.hero_text);

        assertEquals("未标记时是白字", white, btn.getCurrentTextColor());
        Object idleBg = btn.getBackground();

        btn.performClick();
        assertNotSame("标记后背景应换成「已生效」那一张", idleBg, btn.getBackground());
        assertEquals("标记后是紫字（实白底上才读得清）", heroText, btn.getCurrentTextColor());

        btn.performClick();
        assertEquals("撤销后字色要回到白", white, btn.getCurrentTextColor());
        assertNotSame("撤销后背景也要换回来", idleBg, btn.getBackground());
    }

    /** 「明天」按钮动的是明天，不能顺手把今天也标了。 */
    @Test
    public void tappingTomorrowButton_togglesTomorrowOnly() {
        resetPrefs();
        MainActivity a = launch();
        TextView btn = a.findViewById(R.id.btn_day_off_tomorrow);
        assertNotNull("今日卡里应有「明天不上班」按钮", btn);

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        boolean work = MainActivity.isWorkDayType(
                WeekSchedule.dayType(tomorrow, Prefs.getRule(ctx())));

        btn.performClick();
        if (work) {
            assertTrue("上班日点一下应把明天标成请假", Prefs.isVacation(ctx(), tomorrow));
            assertFalse("今天不该被标", Prefs.isVacation(ctx(), today));
            assertTrue("标记后文案应说「明天已请假」", btn.getText().toString().equals("明天已请假"));
        } else {
            assertTrue("休息日点一下应把明天标成加班", Prefs.isOvertime(ctx(), tomorrow));
            assertFalse("今天不该被标", Prefs.isOvertime(ctx(), today));
            assertTrue("标记后文案应说「明天已加班」", btn.getText().toString().equals("明天已加班"));
        }
        assertTrue("文案要说是明天", btn.getText().toString().startsWith("明天"));

        btn.performClick();
        if (work) {
            assertFalse("再点一下应撤销明天的请假", Prefs.isVacation(ctx(), tomorrow));
        } else {
            assertFalse("再点一下应撤销明天的加班", Prefs.isOvertime(ctx(), tomorrow));
        }
    }

    /** 一键标记只动那两天，别把别的日子的标记顺手清了。 */
    @Test
    public void tappingButton_leavesOtherDaysAlone() {
        resetPrefs();
        LocalDate other = LocalDate.now().plusDays(3);
        Prefs.addVacation(ctx(), other);
        Prefs.addOvertime(ctx(), other.plusDays(1));

        MainActivity a = launch();
        a.findViewById(R.id.btn_day_off).performClick();
        a.findViewById(R.id.btn_day_off_tomorrow).performClick();

        assertTrue("别处的请假标记不该被清掉", Prefs.getVacationDates(ctx()).contains(other));
        assertTrue("别处的加班标记不该被清掉",
                Prefs.getOvertimeDates(ctx()).contains(other.plusDays(1)));
    }

    // ---------- 日历回到首页（v1.34） ----------

    /** 日历不再是跳转页：格子和图例都在首页视图树里，进 App 就能看见。 */
    @Test
    public void calendar_isEmbeddedInHomePage() {
        resetPrefs();
        MainActivity a = launch();
        LinearLayout rows = a.findViewById(R.id.cal_rows);
        assertNotNull("首页应有日历格子容器", rows);
        assertEquals("应是 6 行 × 7 格", 6, rows.getChildCount());
        assertEquals(7, ((LinearLayout) rows.getChildAt(0)).getChildCount());
        assertNotNull("图例也应内嵌在首页", a.findViewById(R.id.legend_row));
        assertNotNull("月份标题也应内嵌在首页", a.findViewById(R.id.tv_cal_title));
    }

    /** 两个按钮并排等宽：一个占满一行、另一个藏起来，就还是「得找」的老样子。 */
    @Test
    public void dayOffButtons_shareOneRowEqually() {
        resetPrefs();
        MainActivity a = launch();
        TextView today = a.findViewById(R.id.btn_day_off);
        TextView tomorrow = a.findViewById(R.id.btn_day_off_tomorrow);
        assertTrue("两个按钮应在同一个横向容器里", today.getParent() == tomorrow.getParent());
        LinearLayout row = (LinearLayout) today.getParent();
        assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
        assertEquals("等宽两列", 2, row.getChildCount());
        assertEquals(((LinearLayout.LayoutParams) today.getLayoutParams()).weight,
                ((LinearLayout.LayoutParams) tomorrow.getLayoutParams()).weight, 0.001f);
    }
}
