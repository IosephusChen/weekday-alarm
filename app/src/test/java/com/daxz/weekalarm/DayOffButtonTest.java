package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import java.time.LocalDate;

/**
 * 「今天不上班」一键标记（v1.28.1）。
 *
 * <p>加这个按钮的理由很实际：标一天假原本要「滚到日历 → 找到今天 → 点 → 确认」四步，
 * 而「今天临时有事」恰恰是最急的那一种操作。现在今日卡里点一下就完事，再点一下撤销。
 *
 * <p>这里钉四件事：文案按班休切换、两个方向都能来回切、普通闹钟列表不受影响、
 * 以及日历页确实独立出去了（主界面只留入口行）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DayOffButtonTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    // ---------- 纯函数：文案 ----------

    @Test
    public void text_onWorkDay_offersDayOff() {
        assertEquals("今天不上班", MainActivity.dayOffButtonText(WeekSchedule.DayType.WORK, false));
        assertEquals("今天不上班 ✓（点击恢复）",
                MainActivity.dayOffButtonText(WeekSchedule.DayType.WORK, true));
    }

    @Test
    public void text_onMakeupWorkDay_alsoOffersDayOff() {
        // 调休补班日也是上班日：用户那天照样想请假，按钮不该变成「今天要加班」
        assertEquals("今天不上班",
                MainActivity.dayOffButtonText(WeekSchedule.DayType.MAKEUP_WORK, false));
    }

    @Test
    public void text_onRestDay_offersOvertime() {
        assertEquals("今天要加班", MainActivity.dayOffButtonText(WeekSchedule.DayType.REST, false));
        assertEquals("今天要加班 ✓（点击恢复）",
                MainActivity.dayOffButtonText(WeekSchedule.DayType.REST, true));
    }

    @Test
    public void text_onLegalHoliday_offersOvertime() {
        // 法定假按休息处理：那天想上班才是特例
        assertEquals("今天要加班",
                MainActivity.dayOffButtonText(WeekSchedule.DayType.HOLIDAY, false));
    }

    @Test
    public void isWorkDayType_coversWorkAndMakeupOnly() {
        assertTrue(MainActivity.isWorkDayType(WeekSchedule.DayType.WORK));
        assertTrue(MainActivity.isWorkDayType(WeekSchedule.DayType.MAKEUP_WORK));
        assertFalse(MainActivity.isWorkDayType(WeekSchedule.DayType.REST));
        assertFalse(MainActivity.isWorkDayType(WeekSchedule.DayType.HOLIDAY));
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
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView btn = a.findViewById(R.id.btn_day_off);
        assertNotNull("今日卡里应有「今天不上班」按钮", btn);

        LocalDate today = LocalDate.now();
        boolean work = MainActivity.isWorkDayType(
                WeekSchedule.dayType(today, Prefs.getRule(ctx())));

        btn.performClick();
        if (work) {
            assertTrue("上班日点一下应标成请假", Prefs.isVacation(ctx(), today));
        } else {
            assertTrue("休息日点一下应标成加班", Prefs.isOvertime(ctx(), today));
        }
        assertTrue("标记后按钮文案要带 ✓", btn.getText().toString().contains("✓"));

        btn.performClick();
        if (work) {
            assertFalse("再点一下应撤销请假", Prefs.isVacation(ctx(), today));
        } else {
            assertFalse("再点一下应撤销加班", Prefs.isOvertime(ctx(), today));
        }
        assertFalse("撤销后文案不该还带 ✓", btn.getText().toString().contains("✓"));
    }

    /** 一键标记只动「今天」，别把别的日子的标记顺手清了。 */
    @Test
    public void tappingButton_leavesOtherDaysAlone() {
        resetPrefs();
        LocalDate other = LocalDate.now().plusDays(3);
        Prefs.addVacation(ctx(), other);
        Prefs.addOvertime(ctx(), other.plusDays(1));

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.findViewById(R.id.btn_day_off).performClick();

        assertTrue("别处的请假标记不该被清掉", Prefs.getVacationDates(ctx()).contains(other));
        assertTrue("别处的加班标记不该被清掉",
                Prefs.getOvertimeDates(ctx()).contains(other.plusDays(1)));
    }

    // ---------- 日历独立成页 ----------

    /** 主界面只留入口行，点进去是 CalendarActivity。 */
    @Test
    public void calendarEntry_opensCalendarPage() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowActivity shadow = Shadows.shadowOf(a);
        a.findViewById(R.id.row_calendar_entry).performClick();
        Intent next = shadow.getNextStartedActivity();
        assertNotNull("入口行应打开日历页", next);
        assertEquals(CalendarActivity.class.getName(), next.getComponent().getClassName());
    }

    /** 入口行摘要要能一眼看出今天班休 + 有没有标记，否则等于把日历藏了却没给替代信息。 */
    @Test
    public void calendarEntry_summaryMentionsTodayState() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        String text = ((TextView) a.findViewById(R.id.tv_cal_entry_sub)).getText().toString();
        assertTrue("摘要应说明今天班休，实际：" + text,
                text.startsWith("今天上班") || text.startsWith("今天休息")
                        || text.startsWith("今天补班") || text.startsWith("今天法定假"));
        assertTrue("摘要应说明标记情况，实际：" + text, text.contains("标记")
                || text.contains("请假") || text.contains("加班"));
    }
}
