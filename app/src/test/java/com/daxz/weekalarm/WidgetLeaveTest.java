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
        assertEquals("明天已请假 · 点此恢复",
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
        assertEquals("明天已加班 · 点此恢复",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.REST, true));
    }

    @Test
    public void actionText_legalHoliday_offersOvertime() {
        assertEquals("明天要加班",
                WeekScheduleWidget.actionText(WeekSchedule.DayType.HOLIDAY, false));
    }

    /** 模型里的文案必须和当前标记一致，否则点完看到的还是旧字。 */
    @Test
    public void model_actionMatchesTomorrowState() {
        resetPrefs();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        WeekSchedule.DayType type = WeekSchedule.dayType(tomorrow, Prefs.getRule(ctx()));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;

        assertEquals(WeekScheduleWidget.actionText(type, false),
                WeekScheduleWidget.computeModel(ctx()).action);

        if (work) {
            Prefs.addVacation(ctx(), tomorrow);
        } else {
            Prefs.addOvertime(ctx(), tomorrow);
        }
        assertEquals("标记之后文案要跟着变", WeekScheduleWidget.actionText(type, true),
                WeekScheduleWidget.computeModel(ctx()).action);
    }

    // ---------- 广播：点一下标记，再点一下撤销 ----------

    private void sendToggle() {
        Intent i = new Intent(ctx(), BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_TOGGLE_TOMORROW);
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
