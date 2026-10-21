package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * 过去日期的请假 / 加班记录（v1.35.0，接替 v1.34.3 的同一组用例）。
 *
 * <p>行为在 v1.35.0 反过来了，理由也反了：
 *
 * <ul>
 *   <li>v1.34.3：过去不影响闹钟，留着只会让格子与统计打架 → <b>每次回前台清掉</b>。</li>
 *   <li>v1.35.0：用户想「看看这个月哪几天请了假」，清掉就看不见了 → <b>留着当记录</b>，
 *       只读、可删、不计入统计、不被批量清除。</li>
 * </ul>
 *
 * <p>分界线是「今天」：今天及以后是<b>排程开关</b>（改它闹钟会变，所以「清除」清这一段），
 * 今天之前是<b>记录</b>（闹钟早响过了，改它不影响任何事，但翻日历该看得见）。
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

    /** 往后找第一个上班日 / 休息日，避免测试结果取决于跑测试那天是星期几。 */
    private LocalDate nextDayOf(boolean work) {
        LocalDate d = LocalDate.now();
        for (int i = 0; i < 21; i++) {
            LocalDate x = d.plusDays(i);
            if (DayOffAction.isWorkDay(DayOffAction.typeOf(ctx(), x)) == work) return x;
        }
        throw new AssertionError("三周内找不到符合条件的日子");
    }

    // ---------- 过去：只读、可删 ----------

    /** 点昨天：不弹对话框、不写入，只提示「已经过去」。 */
    @Test
    public void pastDay_rejectsNewMark() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate yesterday = LocalDate.now().minusDays(1);

        clickDay(panelOf(a), yesterday);

        assertFalse("过去的日子不该被标上请假", Prefs.isVacation(ctx(), yesterday));
        assertFalse("也不该被标上加班", Prefs.isOvertime(ctx(), yesterday));
        assertNull("过去且没标记的日子不该弹对话框", latestDialog());
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("应给一句说明，而不是静默失败", toast);
        assertTrue("提示要说清是日期已过，实际：" + toast, toast.contains("已经过去"));
    }

    /** 过去已有的记录仍可删除——标错了要能改，否则看着一个清不掉的「假」字。 */
    @Test
    public void pastDay_existingMarkCanBeDeleted() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Prefs.addVacation(ctx(), yesterday);

        clickDay(panelOf(a), yesterday);

        AlertDialog d = latestDialog();
        assertNotNull("过去有记录时应弹出删除确认", d);
        assertEquals("只该列出请假一项", 1, itemsOf(d).length);
        assertEquals("删除请假记录", itemsOf(d)[0]);

        clickItem(d, 0);
        assertFalse("点一下应能删掉存量记录", Prefs.isVacation(ctx(), yesterday));
    }

    /** 同一天既有请假又有加班时，两个删除项都要列出来，分别删。 */
    @Test
    public void pastDay_bothMarks_listedSeparately() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Prefs.addVacation(ctx(), yesterday);
        Prefs.addOvertime(ctx(), yesterday);

        clickDay(panelOf(a), yesterday);

        AlertDialog d = latestDialog();
        assertNotNull(d);
        String[] items = itemsOf(d);
        assertEquals("两项：请假 + 加班", 2, items.length);
        assertEquals("删除请假记录", items[0]);
        assertEquals("删除加班记录", items[1]);

        clickItem(d, 0);
        assertFalse("请假删掉了", Prefs.isVacation(ctx(), yesterday));
        assertTrue("加班应还在——一次只删一项", Prefs.isOvertime(ctx(), yesterday));
    }

    // ---------- 今天及以后：两个选项都给 ----------

    /**
     * 上班日给「请假」「加班」两项。
     *
     * <p>这是 v1.35.0 的核心修复：以前按班休二选一，工作日的加班根本没有入口，
     * 用户想记「白天上班、晚上补工」时无处可点。
     */
    @Test
    public void workday_offersBothOptions() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate work = nextDayOf(true);

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
        LocalDate rest = nextDayOf(false);

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
        LocalDate work = nextDayOf(true);

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
        LocalDate work = nextDayOf(true);

        clickDay(panelOf(a), work);
        clickItem(latestDialog(), 0);   // 「标记为请假」
        assertTrue(Prefs.isVacation(ctx(), work));

        clickDay(panelOf(a), work);
        String[] items = itemsOf(latestDialog());
        assertEquals("取消请假", items[0]);
        assertEquals("加班项照旧", "标记为加班", items[1]);
    }

    /** 今天和明天照常能标——拦的只是「已经过完」的日子。 */
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

    // ---------- 清除范围：只清今天及以后 ----------

    /** 清除只动今天及以后，过去一个不动。 */
    @Test
    public void clearUpcoming_onlyRemovesUpcoming() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        LocalDate past = today.minusDays(2);
        LocalDate future = today.plusDays(3);

        Prefs.addVacation(ctx(), past);
        Prefs.addVacation(ctx(), today);
        Prefs.addVacation(ctx(), future);

        int removed = Prefs.clearUpcomingMarks(ctx(), true);

        assertEquals("应清掉今天 + 未来共 2 天", 2, removed);
        assertTrue("过去的记录必须留着", Prefs.isVacation(ctx(), past));
        assertFalse("今天的应被清掉", Prefs.isVacation(ctx(), today));
        assertFalse("未来的应被清掉", Prefs.isVacation(ctx(), future));
    }

    @Test
    public void clearUpcoming_isIdempotent() {
        resetPrefs();
        Prefs.addVacation(ctx(), LocalDate.now().minusDays(1));
        assertEquals("第一次没东西可清", 0, Prefs.clearUpcomingMarks(ctx(), true));
        assertEquals("再清一次仍是 0", 0, Prefs.clearUpcomingMarks(ctx(), true));
        assertTrue("过去记录还在", Prefs.isVacation(ctx(), LocalDate.now().minusDays(1)));
    }

    /** 打开 App 不再清过去——这是 v1.34.3 的行为，v1.35.0 明确回退。 */
    @Test
    public void openingApp_keepsPastMarks() {
        resetPrefs();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Prefs.addVacation(ctx(), yesterday);
        Prefs.addOvertime(ctx(), yesterday);

        launch();   // onCreate + onResume

        assertTrue("打开 App 后过去的请假记录应还在", Prefs.isVacation(ctx(), yesterday));
        assertTrue("打开 App 后过去的加班记录应还在", Prefs.isOvertime(ctx(), yesterday));
    }

    /**
     * 统计说几天，就清掉几天。
     *
     * <p>v1.34.3 之前这里会多清（过去的看不见的也被清）。现在统计只算今天及以后、
     * 清除也只清今天及以后，两个数字天然相等，过去的记录不受影响。
     */
    @Test
    public void clearButton_countMatchesWhatGetsCleared() {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today.minusDays(1));   // 过去：记录
        Prefs.addVacation(ctx(), today.plusDays(1));
        Prefs.addVacation(ctx(), today.plusDays(2));

        MainActivity a = launch();
        TextView tv = a.findViewById(R.id.tv_vacation);
        assertEquals("统计只算今天及以后", "请假 2 天 · 清除", tv.getText().toString());

        tv.performClick();
        AlertDialog d = latestDialog();
        assertNotNull("应弹出清除确认", d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse("未来两天清掉了", Prefs.isVacation(ctx(), today.plusDays(1)));
        assertFalse(Prefs.isVacation(ctx(), today.plusDays(2)));
        assertTrue("过去的记录不该被一起清掉", Prefs.isVacation(ctx(), today.minusDays(1)));
        assertEquals("统计归零后回到占位文案", "请假标记",
                ((TextView) a.findViewById(R.id.tv_vacation)).getText().toString());
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
        LocalDate work = nextDayOf(true);
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
