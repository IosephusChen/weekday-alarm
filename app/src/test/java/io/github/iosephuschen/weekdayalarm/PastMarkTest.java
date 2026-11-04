package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
import java.util.Arrays;

/**
 * 日历上的请假 / 加班标记（v0.52.0）。
 *
 * <p>这一组用例随语义改过三轮：
 *
 * <ul>
 *   <li>v0.50.1：过去不影响闹钟，留着只会让格子与统计打架 → <b>拒绝新标记</b>；</li>
 *   <li>v0.51.0：用户想回看，改成「过去只读、可删」；</li>
 *   <li>v0.52.0：用户要补记「上周三请了假」→ <b>过去也能标</b>，与未来同一套菜单，
 *       只是标题标「已过去」、Toast 说「补记」。</li>
 * </ul>
 *
 * <p>现在标记没有时效之分，过去与未来共用一套菜单；改错了就点那一天取消。
 * 过去标记不参与闹钟排程（排程从今天往后逐日算）。
 *
 * <p>v0.52.1 起日历下方不再有「统计 · 清除」按钮：批量清除一次性删掉全部历史，
 * 风险远大于收益，取消统一走「点某一天 → 取消」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PastMarkTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private MainActivity launch() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    /**
     * 切成 7 天一轮（周一到周五上班），并建两个班次。
     *
     * <p>v1.11.2 起「班次」只属于轮班：双休 / 单休 / 大小周的菜单里没有调班，
     * 休息日的加班也不问上哪个班。要测那套交互，班表必须先是轮班。
     */
    private void useCycle() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), 0b0011111L);
        Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));   // 周一
        Prefs.setShifts(ctx(), Arrays.asList(
                new Shift(1, "白班", 8 * 60, Shift.END_UNKNOWN, 0),
                new Shift(2, "夜班", 20 * 60, Shift.END_UNKNOWN, 0)));
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

    /**
     * 点开某天弹出的列表式对话框；没有对话框时返回 null。
     *
     * <p>v0.74.0 起这张列表是自绘的（{@code DialogKit.showPick} 往 {@code dlg_pick_list}
     * 里加卡片），不再是 AlertDialog 的 {@code setItems} 系统列表——按卡片点击、
     * 按位置取文案。
     */
    private AlertDialog latestDialog() {
        return (AlertDialog) ShadowAlertDialog.getLatestDialog();
    }

    /** 对话框里的选项卡容器。 */
    private LinearLayout listOf(AlertDialog d) {
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull("自绘列表应有容器", list);
        return list;
    }

    /** 点列表对话框的第 index 项。 */
    private void clickItem(AlertDialog d, int index) {
        listOf(d).getChildAt(index).performClick();
        ShadowLooper.idleMainLooper();
    }

    /** 选项卡主文案（卡片第一个子视图是文字列，列里第一个 TextView 是主文案）。 */
    private static String optionTitle(View card) {
        ViewGroup col = (ViewGroup) ((ViewGroup) card).getChildAt(0);
        return String.valueOf(((TextView) col.getChildAt(0)).getText());
    }

    private String[] itemsOf(AlertDialog d) {
        LinearLayout list = listOf(d);
        int n = list.getChildCount();
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = optionTitle(list.getChildAt(i));
        return out;
    }

    /** 取对话框标题文本（自绘布局里标题是 tv_pick_title）。 */
    private CharSequence titleOf(AlertDialog d) {
        View t = d.findViewById(R.id.tv_pick_title);
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

    /**
     * 点昨天的上班日：正常弹出「请假」，不再被「已经过去」挡回去。
     *
     * <p>双休没有班次（v1.11.2），上班日只有「不去了」这一个动作——
     * 想换几点起，那是轮班的事。
     */
    @Test
    public void pastWorkday_offersLeaveOnly() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate past = dayOf(true, true);

        clickDay(panelOf(a), past);

        AlertDialog d = latestDialog();
        assertNotNull("过去的日子也该弹出选项", d);
        String[] items = itemsOf(d);
        assertEquals("双休的上班日只有请假一项", 1, items.length);
        assertEquals("标记为请假", items[0]);
        CharSequence title = titleOf(d);
        assertNotNull("对话框应有标题", title);
        assertTrue("标题应标明已过去，实际：" + title, title.toString().contains("已过去"));
    }

    /** 轮班下上班日才有「调班」——班次是轮班的东西。 */
    @Test
    public void pastWorkdayInCycleMode_offersLeaveAndShift() throws Exception {
        resetPrefs();
        useCycle();
        MainActivity a = launch();
        LocalDate past = dayOf(true, true);

        clickDay(panelOf(a), past);

        String[] items = itemsOf(latestDialog());
        assertEquals("轮班的上班日给两项", 2, items.length);
        assertEquals("标记为请假", items[0]);
        assertEquals("第二项叫调班", "调班", items[1]);
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

    /**
     * 过去的日子也只能占一个标记：标加班会把请假顶掉（v1.11.0）。
     *
     * <p>旧版允许同一天既请假又加班，格子上印「假加」——那是个用户看不懂的状态。
     * 现在一天只有一个值，后标的覆盖先标的。
     */
    @Test
    public void pastDay_overtimeReplacesLeave() throws Exception {
        resetPrefs();
        useCycle();
        LocalDate past = dayOf(true, true);
        Prefs.addVacation(ctx(), past);

        MainActivity a = launch();
        clickDay(panelOf(a), past);
        clickItem(latestDialog(), 1);   // 上班日第二项「调班」
        clickItem(latestDialog(), 1);   // 上哪个班 → 夜班

        assertFalse("请假应被顶掉", Prefs.isVacation(ctx(), past));
        assertTrue("加班补上了", Prefs.isOvertime(ctx(), past));
        assertFalse("「假加」这个状态不该再出现", hasLabelNear(a, "假加"));
    }

    // ---------- 今天及以后：照旧 ----------

    /**
     * 双休的上班日只有「请假」（v1.11.2）。
     *
     * <p>v0.51.0 起这里一直是两项，第二项叫过「加班」、v1.11.1 改成「调班」。
     * 但双休根本没有班可调，那个入口从头到尾都是错的——现在收掉。
     * 「上班日能标一件事」这条 v0.51.0 的底线仍然守着。
     */
    @Test
    public void workday_offersLeaveOnly() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate work = dayOf(true, false);

        clickDay(panelOf(a), work);

        AlertDialog d = latestDialog();
        assertNotNull("上班日应弹出选项", d);
        String[] items = itemsOf(d);
        assertEquals("双休的上班日只有请假一项", 1, items.length);
        assertEquals("标记为请假", items[0]);
    }

    /**
     * 双休的休息日：点「标记为加班」直接记一笔，不问上哪个班（v1.11.2）。
     *
     * <p>班次表里还留着以前建的白班，但那不是双休的班——双休只有「去」和「不去」，
     * 没有「几点去」。问了反而逼用户在两个早已不用的班次里做选择。
     */
    @Test
    public void restDayOvertime_withoutShiftModel_justRecords() throws Exception {
        resetPrefs();
        Prefs.setShifts(ctx(), Arrays.asList(
                new Shift(1, "白班", 8 * 60, Shift.END_UNKNOWN, 0)));
        MainActivity a = launch();
        LocalDate rest = dayOf(false, false);

        clickDay(panelOf(a), rest);
        assertEquals("休息日只有加班一项", 1, itemsOf(latestDialog()).length);
        clickItem(latestDialog(), 0);

        assertEquals("不问班次，记成「加班（未指定班次）」",
                Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY), Prefs.getDayShift(ctx(), rest));
        assertNull("不该弹出第二层", ShadowAlertDialog.getLatestAlertDialog());
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

    /**
     * 休息日标加班会接着问「上哪个班」，选完存的是班次 id（v1.11.0）。
     *
     * <p>这是这一版的核心交互：从前一拨开关就完事，App 只知道「这天要去」，
     * 不知道几点去。两层弹窗的顺序也有讲究——第一层先关，第二层才弹得出来。
     */
    @Test
    public void restDayOvertime_asksWhichShift_thenStoresIt() throws Exception {
        resetPrefs();
        useCycle();
        MainActivity a = launch();
        LocalDate rest = dayOf(false, false);

        clickDay(panelOf(a), rest);
        clickItem(latestDialog(), 0);   // 休息日第一项「标记为加班」

        AlertDialog pick = latestDialog();
        assertNotNull("选完「标记为加班」应接着问上哪个班", pick);
        assertTrue("第二层标题要写清问的是什么",
                String.valueOf(titleOf(pick)).contains("上哪个班"));
        assertEquals("两个班次都列出来", 2, itemsOf(pick).length);

        clickItem(pick, 1);   // 夜班
        assertEquals("存的是夜班的 id", Integer.valueOf(2), Prefs.getDayShift(ctx(), rest));
        assertEquals("那天从此有确定的班次", "夜班",
                WeekSchedule.shiftOn(rest, Prefs.getRule(ctx())).name);
    }

    /** 轮班的工作日能换成别的班，并且算一条「和排班不一样」的例外（一天只有一条记录）。 */
    @Test
    public void workdayShift_canBeMarked_asException() throws Exception {
        resetPrefs();
        useCycle();
        MainActivity a = launch();
        LocalDate work = dayOf(true, false);

        clickDay(panelOf(a), work);
        clickItem(latestDialog(), 1);   // 上班日第二项「调班」
        clickItem(latestDialog(), 1);   // 上哪个班 → 夜班

        assertTrue("工作日加班应能记上", Prefs.isOvertime(ctx(), work));
        assertFalse("它只是「这天上了哪个班」的记录，不该让首页按钮显示「已加班 / 已请假」",
                DayOffAction.isMarked(ctx(), work));
        assertFalse("但它不是请假", Prefs.isVacation(ctx(), work));
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
        assertEquals("双休没有调班这一项", 1, items.length);
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

    /** 打开 App 不清任何标记——过去与未来都保留。 */
    @Test
    public void openingApp_keepsAllMarks() {
        resetPrefs();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate dayBefore = LocalDate.now().minusDays(2);
        Prefs.addVacation(ctx(), yesterday);
        Prefs.addOvertime(ctx(), dayBefore);   // v1.11.0：一天只能一个值，用两个日期

        launch();   // onCreate + onResume

        assertTrue("打开 App 后过去的请假记录应还在", Prefs.isVacation(ctx(), yesterday));
        assertTrue("打开 App 后过去的加班记录应还在", Prefs.isOvertime(ctx(), dayBefore));
    }

    // ---------- 渲染 ----------

    /** 一天只印一个标签：后标的加班顶掉请假，格子上不会再出现「假加」。 */
    @Test
    public void oneMarkPerDay_rendersSingleLabel() throws Exception {
        resetPrefs();
        LocalDate today = LocalDate.now();
        Prefs.addVacation(ctx(), today);
        Prefs.addOvertime(ctx(), today);   // 覆盖请假

        MainActivity a = launch();

        assertFalse("「假加」这个状态已不存在", hasLabel(a, "假加"));
        assertTrue("只剩「加班」", hasLabelNear(a, "加班"));
    }

    /**
     * 只有加班时印「加班」，不受班休限制——工作日也能显示。
     *
     * <p>2026-10-30 这条红过一次，不是功能坏了：日历只画当前月，
     * 而那天是当月最后一个工作日，{@code dayOf(true, false)} 往后找到的
     * 第一个工作日已经是 11-02，格子根本不在网格里。所以现在先算出差几个月，
     * 点「下一月」翻过去再断言。
     */
    @Test
    public void workdayOvertime_rendersOvertimeLabel() throws Exception {
        resetPrefs();
        LocalDate work = dayOf(true, false);
        Prefs.addOvertime(ctx(), work);

        MainActivity a = launch();

        java.time.YearMonth now = java.time.YearMonth.from(LocalDate.now());
        java.time.YearMonth target = java.time.YearMonth.from(work);
        int months = (target.getYear() - now.getYear()) * 12
                + (target.getMonthValue() - now.getMonthValue());
        for (int i = 0; i < months; i++) {
            a.findViewById(R.id.btn_next).performClick();
        }

        assertTrue("工作日加班也应显示「加班」", hasLabel(a, "加班"));
    }

    /**
     * 刷新复用同一批格子（v0.52.2）。
     *
     * <p>以前每次 refresh 都重建 42 个格子，首页回到前台、改设置、跨天各来一遍。
     * 改成复用后，View 实例必须还是原来那批——若哪天又被改回 removeAllViews，
     * 这条会失败，提醒改的人「这不是等价重构，是性能回退」。
     */
    @Test
    public void calendarGrid_isReusedOnRefresh() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LinearLayout rows = a.findViewById(R.id.cal_rows);
        assertEquals("固定 6 行", 6, rows.getChildCount());
        View first = ((ViewGroup) rows.getChildAt(0)).getChildAt(0);

        Method m = MainActivity.class.getDeclaredMethod("refreshAll");
        m.setAccessible(true);
        m.invoke(a);
        ShadowLooper.idleMainLooper();

        assertSame("刷新应复用同一批格子", first, ((ViewGroup) rows.getChildAt(0)).getChildAt(0));
        assertEquals("行数不变", 6, rows.getChildCount());
    }

    /** 遍历日历格子，看有没有某个标签文本。 */
    /**
     * 在相邻几个月的格子里找标签。
     *
     * <p>{@link #dayOf}(true, true) 是「今天往前数最近的上班日」；今天若是月初，
     * 那个日子落在上个月，而面板默认显示本月——格子不在视图里，{@link #hasLabel}
     * 自然找不到（2026-11-01 早上跑全量时红的就是这一条）。
     */
    private boolean hasLabelNear(MainActivity a, String label) {
        if (hasLabel(a, label)) return true;
        for (int i = 0; i < 3; i++) {
            a.findViewById(R.id.btn_prev).performClick();
            if (hasLabel(a, label)) return true;
        }
        return false;
    }

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
