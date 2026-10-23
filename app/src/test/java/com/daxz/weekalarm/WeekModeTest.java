package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 周休制度（v1.22）：双休 / 单休（周日）/ 单休（周六）/ 大小周。
 *
 * 这里守住三件事：
 * ① 三种新制度下周六周日的班休判断正确；
 * ② 大小周的老行为一点不变（老用户升级后日历、闹钟必须和升级前一模一样）；
 * ③ 法定节假日与调休补班永远压过制度——放假就是放假，补班就是补班。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WeekModeTest {

    /** 2026-10-24 周六、10-25 周日，两天都不在任何假期里。 */
    private static final LocalDate SAT = LocalDate.of(2026, 10, 24);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 25);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 23);

    private static WeekSchedule.Rule rule(WeekSchedule.Mode mode) {
        return new WeekSchedule.Rule(mode, SAT, false);
    }

    @Test
    public void doubleRestRestsBothDays() {
        WeekSchedule.Rule r = rule(WeekSchedule.Mode.DOUBLE_REST);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SUN, r));
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(FRI, r));
        assertFalse(WeekSchedule.isWorkday(SAT, r));
        assertFalse(WeekSchedule.isWorkday(SUN, r));
    }

    @Test
    public void singleRestSundayWorksOnSaturday() {
        WeekSchedule.Rule r = rule(WeekSchedule.Mode.SINGLE_REST_SUN);
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SUN, r));
        assertTrue(WeekSchedule.isWorkday(SAT, r));
        assertFalse(WeekSchedule.isWorkday(SUN, r));
    }

    @Test
    public void singleRestSaturdayWorksOnSunday() {
        WeekSchedule.Rule r = rule(WeekSchedule.Mode.SINGLE_REST_SAT);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(SUN, r));
        assertFalse(WeekSchedule.isWorkday(SAT, r));
        assertTrue(WeekSchedule.isWorkday(SUN, r));
    }

    @Test
    public void bigSmallBehaviourUnchanged() {
        // anchor = 大周周六；本周双休、下一周单休（休周日）
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.BIG_SMALL, SAT, false);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SAT, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(SUN, r));

        LocalDate nextSat = SAT.plusWeeks(1);
        LocalDate nextSun = SUN.plusWeeks(1);
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(nextSat, r));
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(nextSun, r));
    }

    @Test
    public void bigSmallSmallRestSatStillReversed() {
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.BIG_SMALL, SAT, true);
        LocalDate nextSat = SAT.plusWeeks(1);
        LocalDate nextSun = SUN.plusWeeks(1);
        assertEquals(WeekSchedule.DayType.REST, WeekSchedule.dayType(nextSat, r));
        assertEquals(WeekSchedule.DayType.WORK, WeekSchedule.dayType(nextSun, r));
    }

    @Test
    public void holidayBeatsEveryMode() {
        LocalDate nationalDay = LocalDate.of(2026, 10, 2);   // 国庆假期，周五
        for (WeekSchedule.Mode m : WeekSchedule.Mode.values()) {
            assertEquals("制度 " + m + " 下 10/2 都应是假日",
                    WeekSchedule.DayType.HOLIDAY, WeekSchedule.dayType(nationalDay, rule(m)));
        }
    }

    @Test
    public void makeupWorkdayBeatsEveryMode() {
        LocalDate makeup = LocalDate.of(2026, 10, 10);       // 周六补班
        for (WeekSchedule.Mode m : WeekSchedule.Mode.values()) {
            assertEquals("制度 " + m + " 下 10/10 都应是补班",
                    WeekSchedule.DayType.MAKEUP_WORK, WeekSchedule.dayType(makeup, rule(m)));
        }
    }

    @Test
    public void weekendAlarmFollowsMode() {
        // 「仅休息日」闹钟：单休（周日休）时只在周日响，周六要上班不该响
        Alarm a = new Alarm(1, 9, 0, "懒觉", Alarm.Mode.WEEKEND, true);
        assertFalse(a.appliesTo(SAT, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));
        assertTrue(a.appliesTo(SUN, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));

        // 单休（周六休）反过来
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.SINGLE_REST_SAT), false));
        assertFalse(a.appliesTo(SUN, rule(WeekSchedule.Mode.SINGLE_REST_SAT), false));

        // 双休两天都响
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.DOUBLE_REST), false));
        assertTrue(a.appliesTo(SUN, rule(WeekSchedule.Mode.DOUBLE_REST), false));
    }

    @Test
    public void workdayAlarmFollowsMode() {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        // 单休（周日休）：周六上班要响，周日不响
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));
        assertFalse(a.appliesTo(SUN, rule(WeekSchedule.Mode.SINGLE_REST_SUN), false));
        // 双休：周六日都不响
        assertFalse(a.appliesTo(SAT, rule(WeekSchedule.Mode.DOUBLE_REST), false));
        assertFalse(a.appliesTo(SUN, rule(WeekSchedule.Mode.DOUBLE_REST), false));
    }

    @Test
    public void overtimeStillWakesOnRestDay() {
        // 双休的周六标记加班 → 起床闹钟照响
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        assertTrue(a.appliesTo(SAT, rule(WeekSchedule.Mode.DOUBLE_REST), true));
    }

    @Test
    public void describeText() {
        assertEquals("双休 · 休2天",
                WeekSchedule.describe(rule(WeekSchedule.Mode.DOUBLE_REST), SAT));
        assertEquals("单休 · 周日休",
                WeekSchedule.describe(rule(WeekSchedule.Mode.SINGLE_REST_SUN), SAT));
        assertEquals("单休 · 周六休",
                WeekSchedule.describe(rule(WeekSchedule.Mode.SINGLE_REST_SAT), SAT));
        // 大小周：anchor 当周是大周
        assertEquals("大周 · 休2天",
                WeekSchedule.describe(rule(WeekSchedule.Mode.BIG_SMALL), SAT));
    }

    /**
     * 周日的「本周」必须是刚过去的那个周六所在的周。
     * 旧实现按 ISO 周算，周日会跳到下周六：2026-10-25 是周日，日历这一行是
     * 10/19–10/25（周六 10/24 小周），标签却按 10/31 算成大周，两处对不上。
     */
    @Test
    public void sundayBelongsToTheWeekThatJustEnded() {
        assertEquals(SAT, WeekSchedule.saturdayOf(SUN));
        assertEquals(SAT, WeekSchedule.saturdayOf(SAT));
        assertEquals(SAT, WeekSchedule.saturdayOf(FRI));

        // 10/17 是大周周六 → 10/24 是小周 → 10/25 周日必须跟着这一周算小周
        WeekSchedule.Rule r = new WeekSchedule.Rule(WeekSchedule.Mode.BIG_SMALL,
                SAT.minusWeeks(1), false);
        assertTrue(WeekSchedule.isBigWeekNow(SAT.minusWeeks(1), SAT.minusWeeks(1)));
        assertFalse(WeekSchedule.isBigWeekNow(SAT, SAT.minusWeeks(1)));
        assertFalse(WeekSchedule.isBigWeekNow(SUN, SAT.minusWeeks(1)));
        // 关键：周日与同一行的周六标签一致（旧实现这里会差一周）
        assertEquals(WeekSchedule.describe(r, SAT), WeekSchedule.describe(r, SUN));
        assertEquals("小周 · 休1天", WeekSchedule.describe(r, SUN));
    }

    /**
     * 「本周是」：点开对话框选小周，应写入状态并把基准推到上一周的周六。
     *
     * <p>v1.46.3 起这一行从首页搬到了设置页，所以反射目标也跟着换成 SettingsActivity。
     * 断言没变——写状态、推 anchor 这两件事和界面放哪无关。
     */
    @Test
    public void weekBigDialog_setsStateAndAnchor() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();

        // v1.49.2：「本周是」「小周休哪天」并进了周休制度对话框，不再有独立入口
        SettingsActivity a = org.robolectric.Robolectric
                .buildActivity(SettingsActivity.class).setup().get();
        a.findViewById(R.id.row_week_mode).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        androidx.appcompat.app.AlertDialog d =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出周休制度对话框", d);
        // 先选「大小周」，条件块才会出现；再选「小周」
        ((android.widget.RadioButton) d.findViewById(R.id.rb_big_small)).performClick();
        ((android.widget.RadioButton) d.findViewById(R.id.rb_small)).performClick();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertFalse(Prefs.isWeekBig(c));
        assertEquals(WeekSchedule.saturdayOf(LocalDate.now()).minusWeeks(1), Prefs.getAnchor(c));
    }

    @Test
    public void modePersistsAndDefaultsToBigSmall() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // 没写过这个键时必须是大小周——老用户升级后不能悄悄变成双休
        ctx.getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().remove(Prefs.KEY_WEEK_MODE).commit();
        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx));

        for (WeekSchedule.Mode m : WeekSchedule.Mode.values()) {
            Prefs.setWeekMode(ctx, m);
            assertEquals(m, Prefs.getWeekMode(ctx));
        }
    }

    @Test
    public void illegalModeFallsBackToBigSmall() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().putInt(Prefs.KEY_WEEK_MODE, 99).commit();
        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx));
        ctx.getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().putInt(Prefs.KEY_WEEK_MODE, -3).commit();
        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx));
    }

    @Test
    public void onlyBigSmallNeedsAnchor() {
        assertTrue(WeekSchedule.Mode.BIG_SMALL.needsAnchor());
        assertFalse(WeekSchedule.Mode.DOUBLE_REST.needsAnchor());
        assertFalse(WeekSchedule.Mode.SINGLE_REST_SUN.needsAnchor());
        assertFalse(WeekSchedule.Mode.SINGLE_REST_SAT.needsAnchor());
    }

    // ================= 对话框分层（v1.49.2）=================

    private static final String SP = "daxz_alarm";

    private static SettingsActivity openSettings() {
        Prefs.setGuideSeen(ApplicationProvider.getApplicationContext(), true);
        Prefs.setOnboardDone(ApplicationProvider.getApplicationContext(), true);
        return org.robolectric.Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    private static androidx.appcompat.app.AlertDialog openModeDialog(SettingsActivity a) {
        a.findViewById(R.id.row_week_mode).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        return (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
    }

    /**
     * 大类只有三个：大小周 / 双休 / 单休。
     *
     * <p>v1.49.2 之前是四个（单休拆成周日休、周六休两条），用户要求把具体日子
     * 收到下面条件区，大类不掺细节。
     */
    @Test
    public void dialogOffersThreeCategories() {
        SettingsActivity a = openSettings();
        androidx.appcompat.app.AlertDialog d = openModeDialog(a);
        assertNotNull(d);
        android.widget.RadioGroup rg = d.findViewById(R.id.rg_mode);
        assertNotNull(rg);
        assertEquals("大类应正好三个", 3, rg.getChildCount());
    }

    /** 从双休切到「单休」不指定日子时，默认周日休。 */
    @Test
    public void singleRest_defaultsToSunday() {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setWeekMode(c, WeekSchedule.Mode.DOUBLE_REST);
        Prefs.setSmallRestSat(c, false);

        SettingsActivity a = openSettings();
        androidx.appcompat.app.AlertDialog d = openModeDialog(a);
        ((android.widget.RadioButton) d.findViewById(R.id.rb_single)).performClick();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertEquals("单休默认周日", WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(c));
    }

    /** 上次单休选过周六，切走再切回来应沿用，而不是重置成周日。 */
    @Test
    public void singleRest_remembersLastChoice() {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setWeekMode(c, WeekSchedule.Mode.DOUBLE_REST);
        Prefs.setSmallRestSat(c, true);      // 模拟上次单休选的是周六

        SettingsActivity a = openSettings();
        androidx.appcompat.app.AlertDialog d = openModeDialog(a);
        ((android.widget.RadioButton) d.findViewById(R.id.rb_single)).performClick();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertEquals("应沿用上次的周六休", WeekSchedule.Mode.SINGLE_REST_SAT, Prefs.getWeekMode(c));
    }

    /**
     * 「单休休哪天」的选项只写哪天休，不写哪天上班（v1.49.4）。
     *
     * <p>原来写「周日休（周六上班）」。休一天，另一天自然要上班，括号是把
     * 结论重复一遍；同一个对话框里「小周休哪天」也没写，两处还不一致。
     * 这条钉住，免得下次又有人「顺手补全」。
     */
    @Test
    public void singleRestOptions_sayOnlyTheRestDay() {
        SettingsActivity a = openSettings();
        androidx.appcompat.app.AlertDialog d = openModeDialog(a);
        ((android.widget.RadioButton) d.findViewById(R.id.rb_single)).performClick();

        assertEquals("周日休",
                ((android.widget.RadioButton) d.findViewById(R.id.rb_single_sun)).getText().toString());
        assertEquals("周六休",
                ((android.widget.RadioButton) d.findViewById(R.id.rb_single_sat)).getText().toString());
    }
}
