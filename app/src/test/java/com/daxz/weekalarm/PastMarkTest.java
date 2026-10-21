package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
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
 * 日历上的请假 / 加班标记（v1.35.1）。
 *
 * <p>这一组用例随语义改过三轮：
 *
 * <ul>
 *   <li>v1.34.3：过去不影响闹钟，留着只会让格子与统计打架 → <b>拒绝新标记</b>；</li>
 *   <li>v1.35.0：用户想回看，改成「过去只读、可删」；</li>
 *   <li>v1.35.1：用户要补记「上周三请了假」→ <b>过去也能标</b>，与未来同一套菜单，
 *       只是标题标「已过去」、Toast 说「补记」。</li>
 * </ul>
 *
 * <p>现在标记没有时效之分：统计算全部、清除清全部，过去标记不参与闹钟排程
 * （排程从今天往后逐日算）。
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

    /** 点开某天弹出的列表式对话框；没有对话框时返回 null。 */
    private AlertDialog latestDialog() {
        return (AlertDialog) ShadowAlertDialog.getLatestDialog();
    }

    /** 点列表对话框的第 index 项（setItems 的选项就是这个 ListView 的 item）。 */
    private void clickItem(AlertDialog d, int index) {
        ListView lv = d.getListView();
        assertNotNull("列表式对话框应有 ListView", lv);
        lv.performItemClick(null, index, lv.getItemIdAtPosition(index));
        ShadowLooper.idleMainLooper();
    }

    private String[] itemsOf(AlertDialog d) {
        ListView lv = d.getListView();
        assertNotNull(lv);
        int n = lv.getAdapter().getCount();
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = String.valueOf(lv.getAdapter().getItem(i));
        return out;
    }

    /** 取对话框标题文本（AppCompat 的标题 TextView 用 alertTitle 这个 id）。 */
    private CharSequence titleOf(AlertDialog d) {
        View t = d.findViewById(androidx.appcompat.R.id.alertTitle);
        return t instanceof TextView ? ((TextView) t).getText() : null;
    }

    /** 往前 / 往后找第一个上班日或休息日，避免测试结果取决于跑测试那天是星期几。 */
    private LocalDate dayOf(boolean work, boolean past) {
        LocalDate base = LocalDate.now();
        for (int i = 1; i <= 21; i++) {
            LocalDate x = past ? base.minusDays(i) : base.plusDays(i);
            if (DayOffAction.isWorkDay(DayOffAction.typeOf(ctx(), x)) == work) return x;
        }
        throw new AssertionError("三周内找不到符合条件的日子");
    }

    // ---------- 过去：可以补记 ----------

    /** 点昨天的上班日：正常弹出「请假 / 加班」两项，不再被「已经过去」挡回去。 */
    @Test
    public void pastWorkday_offersBothOptions() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate past = dayOf(true, true);

        clickDay(panelOf(a), past);

        AlertDialog d = latestDialog();
        assertNotNull("过去的日子也该弹出选项", d);
        String[] items = itemsOf(d);
        assertEquals("上班日给两项", 2, items.length);
        assertEquals("标记为请假", items[0]);
        assertEquals("标记为加班", items[1]);
        CharSequence title = titleOf(d);
        assertNotNull("对话框应有标题", title);
        assertTrue("标题应标明已过去，实际：" + title, title.toString().contains("已过去"));
    }

    /** 补记昨天的请假：真的写进 Prefs，Toast 用「补记」而不是「当天闹钟跳过」。 */
    @Test
    public void pastDay_canBeMarkedAsVacation() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate past = dayOf(true, true);

        clickDay(panelOf(a), past);
        clickItem(latestDialog(), 0);   // 「标记为请假」

        assertTrue("过去的日子应能补记请假", Prefs.isVacation(ctx(), past));
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull(toast);
        assertTrue("应说明是补记，实际：" + toast, toast.contains("补记"));
        assertFalse("不该承诺过去的闹钟会跳过，实际：" + toast, toast.contains("跳过"));
    }

    /** 补记过去休息日的加班。 */
    @Test
    public void pastRestDay_canBeMarkedAsOvertime() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate past = dayOf(false, true);

        clickDay(panelOf(a), past);
        AlertDialog d = latestDialog();
        assertNotNull(d);
        assertEquals("休息日只给加班", 1, itemsOf(d).length);

        clickItem(d, 0);
        assertTrue("过去的休息日应能补记加班", Prefs.isOvertime(ctx(), past));
    }

    /** 补记后再点同一天，选项变成「取消请假」，点一下能删掉。 */
    @Test
    public void pastMarkedDay_showsCancelAndCanBeRemoved() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate past = dayOf(true, true);

        clickDay(panelOf(a), past);
        clickItem(latestDialog(), 0);
        assertTrue(Prefs.isVacation(ctx(), past));

        clickDay(panelOf(a), past);
        String[] items = itemsOf(latestDialog());
        assertEquals("取消请假", items[0]);

        clickItem(latestDialog(), 0);
        assertFalse("再点一下应删掉补记", Prefs.isVacation(ctx(), past));
    }

    /** 过去的日子也能同时补记请假与加班，格子上印「假加」。 */
    @Test
    public void pastDay_bothMarksAllowed() throws Exception {
        resetPrefs();
        LocalDate past = dayOf(true, true);
        Prefs.addVacation(ctx(), past);

        MainActivity a = launch();
        clickDay(panelOf(a), past);
        clickItem(latestDialog(), 1);   // 上班日第二项「标记为加班」

        assertTrue("请假还在", Prefs.isVacation(ctx(), past));
        assertTrue("加班也补上了", Prefs.isOvertime(ctx(), past));
        assertTrue("格子上应出现「假加」", hasLabel(a, "假加"));
    }

    // ---------- 今天及以后：照旧 ----------

    /** 上班日给「请假」「加班」两项（v1.35.0 的核心修复，不能被这次改动带回去）。 */
    @Test
    public void workday_offersBothOptions() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate work = dayOf(true, false);

        clickDay(panelOf(a), work);

        AlertDialog d = latestDialog();
        assertNotNull("上班日应弹出选项", d);
        String[] items = itemsOf(d);
        assertEquals("上班日给两项", 2, items.length);
        assertEquals("标记为请假", items[0]);
        assertEquals("标记为加班", items[1]);
    }

    /** 休息日只给「加班」——休息日本来就不上班，标请假没有意义。 */
    @Test
    public void restDay_offersOnlyOvertime() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate rest = dayOf(false, false);

        clickDay(panelOf(a), rest);

        AlertDialog d = latestDialog();
        assertNotNull(d);
        String[] items = itemsOf(d);
        assertEquals("休息日只给一项", 1, items.length);
        assertEquals("标记为加班", items[0]);
    }

    /** 工作日的加班能标上，且不影响「这天和排班不一样」的判断（首页按钮语义）。 */
    @Test
    public void workdayOvertime_canBeMarked_withoutAffectingDayOffButton() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate work = dayOf(true, false);

        clickDay(panelOf(a), work);
        clickItem(latestDialog(), 1);   // 「标记为加班」

        assertTrue("工作日加班应能记上", Prefs.isOvertime(ctx(), work));
        assertFalse("但那天仍是上班日，首页按钮不该认为已请假", DayOffAction.isMarked(ctx(), work));
    }

    /** 上班日点「标记为请假」后，再点同一天，选项应变成「取消请假」。 */
    @Test
    public void markedDay_showsCancelOption() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate work = dayOf(true, false);

        clickDay(panelOf(a), work);
        clickItem(latestDialog(), 0);   // 「标记为请假」
        assertTrue(Prefs.isVacation(ctx(), work));

        clickDay(panelOf(a), work);
        String[] items = itemsOf(latestDialog());
        assertEquals("取消请假", items[0]);
        assertEquals("加班项照旧", "标记为加班", items[1]);
    }

    /** 今天和明天照常能标。 */
    @Test
    public void todayAndTomorrow_stillMarkable() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate today = LocalDate.now();

        clickDay(panelOf(a), today);
        AlertDialog d1 = latestDialog();
        assertNotNull("今天应弹出选项", d1);
        clickItem(d1, 0);   // 上班日第一项是请假，休息日第一项是加班——都是「标记」
        assertTrue("点第一项后今天应有标记",
                Prefs.isVacation(ctx(), today) || Prefs.isOvertime(ctx(), today));

        clickDay(panelOf(a), today.plusDays(1));
        assertNotNull("明天也应弹出选项", latestDialog());
    }

    // ---------- 统计与清除：都算全部 ----------

    /** 统计算全部，包括补记的过去。 */
    @Test
    public void stats_countPastAndFuture() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today.minusDays(2));
        Prefs.addVacation(ctx(), today.plusDays(3));

        MainActivity a = launch();
        TextView tv = a.findViewById(R.id.tv_vacation);

        assertEquals("过去 + 未来共 2 天", "请假 2 天 · 清除", tv.getText().toString());
    }

    /** 清除清全部：统计说几天，就清掉几天，包括补记的过去。 */
    @Test
    public void clear_removesAllMarks_countMatches() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today.minusDays(1));
        Prefs.addVacation(ctx(), today.plusDays(1));
        Prefs.addVacation(ctx(), today.plusDays(2));

        MainActivity a = launch();
        TextView tv = a.findViewById(R.id.tv_vacation);
        assertEquals("统计算全部", "请假 3 天 · 清除", tv.getText().toString());

        tv.performClick();
        AlertDialog d = latestDialog();
        assertNotNull("应弹出清除确认", d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse("过去的补记也清掉了", Prefs.isVacation(ctx(), today.minusDays(1)));
        assertFalse("未来两天清掉了", Prefs.isVacation(ctx(), today.plusDays(1)));
        assertFalse(Prefs.isVacation(ctx(), today.plusDays(2)));
        assertEquals("统计归零后回到占位文案", "请假标记",
                ((TextView) a.findViewById(R.id.tv_vacation)).getText().toString());
    }

    /** 请假与加班分开清，互不影响。 */
    @Test
    public void clear_isSeparatedByKind() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today.minusDays(1));
        Prefs.addOvertime(ctx(), today.minusDays(1));

        assertEquals(1, Prefs.clearMarks(ctx(), true));

        assertFalse("请假清掉了", Prefs.isVacation(ctx(), today.minusDays(1)));
        assertTrue("加班不受影响", Prefs.isOvertime(ctx(), today.minusDays(1)));
    }

    /** 清除幂等：没东西可清时返回 0，不炸。 */
    @Test
    public void clear_isIdempotent() {
        resetPrefs();
        assertEquals("空集合清 0 条", 0, Prefs.clearMarks(ctx(), true));
        assertEquals("再清一次仍是 0", 0, Prefs.clearMarks(ctx(), true));
    }

    /** 打开 App 不清任何标记——过去与未来都保留。 */
    @Test
    public void openingApp_keepsAllMarks() {
        resetPrefs();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Prefs.addVacation(ctx(), yesterday);
        Prefs.addOvertime(ctx(), yesterday);

        launch();   // onCreate + onResume

        assertTrue("打开 App 后过去的请假记录应还在", Prefs.isVacation(ctx(), yesterday));
        assertTrue("打开 App 后过去的加班记录应还在", Prefs.isOvertime(ctx(), yesterday));
    }

    // ---------- 渲染 ----------

    /** 同一天两个标记，格子上印「假加」——两个字，宽度与「请假」相同。 */
    @Test
    public void bothMarks_renderAsCombinedLabel() throws Exception {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today);
        Prefs.addOvertime(ctx(), today);

        MainActivity a = launch();

        assertTrue("格子上应出现「假加」", hasLabel(a, "假加"));
    }

    /** 只有加班时印「加班」，不受班休限制——工作日也能显示。 */
    @Test
    public void workdayOvertime_rendersOvertimeLabel() throws Exception {
        resetPrefs();
        LocalDate work = dayOf(true, false);
        Prefs.addOvertime(ctx(), work);

        MainActivity a = launch();

        assertTrue("工作日加班也应显示「加班」", hasLabel(a, "加班"));
    }

    /** 遍历日历格子，看有没有某个标签文本。 */
    private boolean hasLabel(MainActivity a, String label) {
        LinearLayout rows = a.findViewById(R.id.cal_rows);
        for (int r = 0; r < rows.getChildCount(); r++) {
            View row = rows.getChildAt(r);
            if (!(row instanceof ViewGroup)) continue;
            ViewGroup rg = (ViewGroup) row;
            for (int c = 0; c < rg.getChildCount(); c++) {
                View cell = rg.getChildAt(c);
                if (!(cell instanceof ViewGroup)) continue;
                ViewGroup cg = (ViewGroup) cell;
                for (int k = 0; k < cg.getChildCount(); k++) {
                    View v = cg.getChildAt(k);
                    if (v instanceof TextView
                            && label.contentEquals(((TextView) v).getText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
