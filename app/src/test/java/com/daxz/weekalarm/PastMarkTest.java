package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;

/**
 * 过去日期的请假 / 加班标记（v1.34.3）。
 *
 * <p>用户报的现象：点今天及以后的日子，下方统计会 +1；点今天之前的日子，统计不动；
 * 可点「清除」时，那些看不见的过去标记也被一起清掉了。三处说的不是同一件事。
 *
 * <p>查下来：统计只算今天及以后是 v1.19.9 的刻意设计（否则半年后会显示「请假 37 天」），
 * 但「过去仍可标记」和「清除清全部」凑在一起就成了漏洞——格子印着「假」、统计不认、
 * 清除又多清。现在两头对齐：过去的日子不再接受新标记，存量的在回到前台时自动清掉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PastMarkTest {

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

    /** 拿到首页内嵌的日历面板，用于直接触发某一天的点击。 */
    private Object panelOf(MainActivity a) throws Exception {
        Field f = MainActivity.class.getDeclaredField("calendarPanel");
        f.setAccessible(true);
        return f.get(a);
    }

    private void clickDay(Object panel, LocalDate d) throws Exception {
        Method m = panel.getClass().getDeclaredMethod("onDayClick", LocalDate.class);
        m.setAccessible(true);
        m.invoke(panel, d);
    }

    // ---------- 过去不能标 ----------

    /** 点昨天标请假：不写入，只提示「已经过去」。 */
    @Test
    public void pastDay_rejectsNewMark() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate yesterday = LocalDate.now().minusDays(1);

        clickDay(panelOf(a), yesterday);

        assertFalse("过去的日子不该被标上请假", Prefs.isVacation(ctx(), yesterday));
        assertFalse("也不该被标上加班", Prefs.isOvertime(ctx(), yesterday));
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("应给一句说明，而不是静默失败", toast);
        assertTrue("提示要说清是日期已过，实际：" + toast, toast.contains("已经过去"));
    }

    /** 存量的过去标记（旧版本留下的）仍然点得掉，否则用户看着一个清不掉的「假」字。 */
    @Test
    public void pastDay_existingMarkCanStillBeRemoved() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        // 按那天的实际类型造存量标记：上班日对应请假，休息日对应加班
        boolean work = DayOffAction.isWorkDay(DayOffAction.typeOf(ctx(), yesterday));
        if (work) {
            Prefs.addVacation(ctx(), yesterday);
        } else {
            Prefs.addOvertime(ctx(), yesterday);
        }
        assertTrue("前置：昨天应带着标记", DayOffAction.isMarked(ctx(), yesterday));

        clickDay(panelOf(a), yesterday);

        assertFalse("点一下应能取消存量标记", DayOffAction.isMarked(ctx(), yesterday));
    }

    /** 今天和未来照常能标——拦的只是「已经过完」的日子。 */
    @Test
    public void todayAndTomorrow_stillMarkable() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate today = LocalDate.now();

        clickDay(panelOf(a), today);
        AlertDialog d1 = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("今天应弹出标记确认框（而不是只给一句提示）", d1);
        d1.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("点确认后今天应被标上", DayOffAction.isMarked(ctx(), today));

        clickDay(panelOf(a), today.plusDays(1));
        AlertDialog d2 = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("明天也应弹出确认框", d2);
        d2.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("明天也应能标记", DayOffAction.isMarked(ctx(), today.plusDays(1)));
    }

    // ---------- 存量清理 ----------

    /** 清理只动今天之前，今天与未来一个不动。 */
    @Test
    public void prunePastMarks_onlyRemovesPast() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        LocalDate past2 = today.minusDays(2);
        LocalDate future = today.plusDays(3);

        Prefs.addVacation(ctx(), past2);
        Prefs.addVacation(ctx(), today);
        Prefs.addVacation(ctx(), future);
        Prefs.addOvertime(ctx(), past2);

        int removed = Prefs.prunePastMarks(ctx());

        assertEquals("应清掉 1 天请假 + 1 天加班", 2, removed);
        assertFalse("过去的请假应被清掉", Prefs.isVacation(ctx(), past2));
        assertFalse("过去的加班应被清掉", Prefs.isOvertime(ctx(), past2));
        assertTrue("今天必须留着", Prefs.isVacation(ctx(), today));
        assertTrue("未来必须留着", Prefs.isVacation(ctx(), future));
    }

    @Test
    public void prunePastMarks_isIdempotent() {
        resetPrefs();
        Prefs.addVacation(ctx(), LocalDate.now().plusDays(1));
        assertEquals("第一次没东西可清", 0, Prefs.prunePastMarks(ctx()));
        assertEquals("再清一次仍是 0", 0, Prefs.prunePastMarks(ctx()));
        assertTrue(Prefs.isVacation(ctx(), LocalDate.now().plusDays(1)));
    }

    /** 回到前台就清：打开 App 后，旧版本留下的过去标记不该还挂在日历上。 */
    @Test
    public void openingApp_prunesPastMarks() {
        resetPrefs();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Prefs.addVacation(ctx(), yesterday);

        launch();   // onCreate + onResume

        assertFalse("打开 App 后过去的标记应已清掉", Prefs.isVacation(ctx(), yesterday));
    }

    // ---------- 统计与清除对得上 ----------

    /**
     * 用户真正踩到的那个不一致：统计说 2 天，清除时却把看不见的过去标记也清了。
     * 现在过去标记在进页面时就被清掉，统计数 = 实际清除数。
     */
    @Test
    public void clearAll_countMatchesWhatGetsCleared() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today.minusDays(1));   // 存量：过去
        Prefs.addVacation(ctx(), today.plusDays(1));
        Prefs.addVacation(ctx(), today.plusDays(2));

        MainActivity a = launch();   // onResume 清掉昨天
        TextView tv = a.findViewById(R.id.tv_vacation);
        assertEquals("统计只算今天及以后", "请假 2 天 · 清除", tv.getText().toString());

        tv.performClick();
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出清除确认", d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // 对话框按钮的点击经 Handler 派发，要让主线程跑一轮回调才会执行
        ShadowLooper.idleMainLooper();

        assertTrue("清除后不该再有请假标记", Prefs.getVacationDates(ctx()).isEmpty());
        assertEquals("统计归零后回到占位文案", "请假标记",
                ((TextView) a.findViewById(R.id.tv_vacation)).getText().toString());
    }
}
