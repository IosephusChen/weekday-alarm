package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 小组件上的「明天不上班」（v1.31）。
 *
 * <p>这个按钮最怕两件事：一是只能请不能撤（点错了得进 App 改），
 * 二是点了之后组件还显示旧文案（用户以为没生效，再点一次又撤销了）。
 * 前者钉文案与开关语义，后者钉「改完立刻刷新」这条链路。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WidgetLeaveTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
    }

    // ---------- 文案 ----------

    @Test
    public void actionText_workDay_offersLeave() {
        assertEquals("明天不上班", WeekScheduleWidget.actionText(WeekSchedule.DayType.WORK, false));
        assertEquals("明天已请假",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.WORK, true));
    }

    /** 调休补班日也是上班日：那天照样要早起，按钮不该变成「明天要加班」。 */
    @Test
    public void actionText_makeupWorkDay_alsoOffersLeave() {
        assertEquals("明天不上班",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.MAKEUP_WORK, false));
    }

    @Test
    public void actionText_restDay_offersOvertime() {
        assertEquals("明天要加班",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.REST, false));
        assertEquals("明天已加班",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.REST, true));
    }

    @Test
    public void actionText_legalHoliday_offersOvertime() {
        assertEquals("明天要加班",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.HOLIDAY, false));
    }

    /** 今天 / 明天两个按钮的文案都要和当前标记一致，否则点完看到的还是旧字。 */
    @Test
    public void model_actionsMatchCurrentState() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        WeekSchedule.DayType todayType = WeekSchedule.dayType(today, rule);
        WeekSchedule.DayType tomorrowType = WeekSchedule.dayType(tomorrow, rule);
        boolean todayWork = todayType == WeekSchedule.DayType.WORK
                || todayType == WeekSchedule.DayType.MAKEUP_WORK;
        boolean tomorrowWork = tomorrowType == WeekSchedule.DayType.WORK
                || tomorrowType == WeekSchedule.DayType.MAKEUP_WORK;

        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        assertEquals(WeekScheduleWidget.actionText("今天", todayType, false), m.todayAction);
        assertEquals(WeekScheduleWidget.actionText("明天", tomorrowType, false), m.tomorrowAction);
        assertFalse(m.todayMarked);
        assertFalse(m.tomorrowMarked);

        if (todayWork) {
            Prefs.addVacation(ctx(), today);
        } else {
            Prefs.addOvertime(ctx(), today);
        }
        if (tomorrowWork) {
            Prefs.addVacation(ctx(), tomorrow);
        } else {
            Prefs.addOvertime(ctx(), tomorrow);
        }
        m = WeekScheduleWidget.computeModel(ctx());
        assertEquals("今天标记后文案要变", WeekScheduleWidget.actionText("今天", todayType, true),
                m.todayAction);
        assertEquals("明天标记后文案要变",
                WeekScheduleWidget.actionText("明天", tomorrowType, true), m.tomorrowAction);
        assertTrue(m.todayMarked);
        assertTrue(m.tomorrowMarked);
    }

    /** 今天和明天互不影响：标了今天，明天的按钮不能跟着变成「已请假」。 */
    @Test
    public void markingToday_doesNotAffectTomorrowButton() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        WeekSchedule.DayType type = WeekSchedule.dayType(today, Prefs.getRule(ctx()));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
        if (work) {
            Prefs.addVacation(ctx(), today);
        } else {
            Prefs.addOvertime(ctx(), today);
        }
        WeekScheduleWidget.WeekModel m = WeekScheduleWidget.computeModel(ctx());
        assertTrue(m.todayMarked);
        assertFalse("明天不该被连带标记", m.tomorrowMarked);
    }

    // ---------- 广播：点一下标记，再点一下撤销 ----------

    private void sendToggle() {
        Intent i = new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_TOGGLE_TOMORROW);
        new BedtimeReceiver().onReceive(ctx(), i);
    }

    private void sendToggle(int dayOffset) {
        Intent i = new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_TOGGLE_DAY)
                .putExtra(BedtimeReceiver.EXTRA_DAY_OFFSET, dayOffset);
        new BedtimeReceiver().onReceive(ctx(), i);
    }

    /**
     * 点一下、再点一下，两个方向都要能来回。
     *
     * <p>不写死「明天是上班日」——跑测试那天是星期几由不得我们。按真实类型决定
     * 该看请假集合还是加班集合，这样周一到周日跑都是同一条断言。
     */
    @Test
    public void toggle_marksThenUnmarksTomorrow() {
        resetPrefs();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        WeekSchedule.DayType type = WeekSchedule.dayType(tomorrow, Prefs.getRule(ctx()));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;

        sendToggle();
        if (work) {
            assertTrue("上班日点一下应标成请假", Prefs.isVacation(ctx(), tomorrow));
        } else {
            assertTrue("休息日点一下应标成加班", Prefs.isOvertime(ctx(), tomorrow));
        }

        sendToggle();
        if (work) {
            assertFalse("再点一下应撤销请假", Prefs.isVacation(ctx(), tomorrow));
        } else {
            assertFalse("再点一下应撤销加班", Prefs.isOvertime(ctx(), tomorrow));
        }
    }

    /** 带 offset 的通用动作：0 = 今天，1 = 明天，各改各的。 */
    @Test
    public void toggleDay_byOffset_hitsTheRightDay() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        WeekSchedule.Rule rule = Prefs.getRule(ctx());

        sendToggle(0);
        if (isWork(today, rule)) {
            assertTrue("offset 0 应标今天", Prefs.isVacation(ctx(), today));
            assertFalse("不该动明天", Prefs.isVacation(ctx(), tomorrow));
        } else {
            assertTrue(Prefs.isOvertime(ctx(), today));
            assertFalse(Prefs.isOvertime(ctx(), tomorrow));
        }

        sendToggle(1);
        if (isWork(tomorrow, rule)) {
            assertTrue("offset 1 应标明天", Prefs.isVacation(ctx(), tomorrow));
        } else {
            assertTrue(Prefs.isOvertime(ctx(), tomorrow));
        }
    }

    /** 脏 offset（比如 5）按明天处理，不会去标一个莫名其妙的日期。 */
    @Test
    public void toggleDay_bogusOffset_fallsBackToTomorrow() {
        resetPrefs();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        sendToggle(5);
        if (isWork(tomorrow, rule)) {
            assertTrue(Prefs.isVacation(ctx(), tomorrow));
        } else {
            assertTrue(Prefs.isOvertime(ctx(), tomorrow));
        }
    }

    private static boolean isWork(LocalDate d, WeekSchedule.Rule rule) {
        WeekSchedule.DayType t = WeekSchedule.dayType(d, rule);
        return t == WeekSchedule.DayType.WORK || t == WeekSchedule.DayType.MAKEUP_WORK;
    }

    /** 只动「明天」，别把别的日子的标记顺手清了。 */
    @Test
    public void toggle_leavesOtherDaysAlone() {
        resetPrefs();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate later = tomorrow.plusDays(4);
        Prefs.addVacation(ctx(), later);
        Prefs.addOvertime(ctx(), later.plusDays(1));

        sendToggle();

        assertTrue("后天的请假标记不该被清掉", Prefs.getVacationDates(ctx()).contains(later));
        assertTrue("更远的加班标记不该被清掉",
                Prefs.getOvertimeDates(ctx()).contains(later.plusDays(1)));
    }

    /** 别的 action 不该被误当成「明天请假」——这个接收器还管着发通知。 */
    @Test
    public void unknownAction_doesNothing() {
        resetPrefs();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Intent i = new Intent(ctx(), BedtimeReceiver.class)
                .setAction("com.daxz.weekalarm.NOT_A_REAL_ACTION");
        new BedtimeReceiver().onReceive(ctx(), i);

        assertFalse(Prefs.isVacation(ctx(), tomorrow));
        assertFalse(Prefs.isOvertime(ctx(), tomorrow));
    }
}
