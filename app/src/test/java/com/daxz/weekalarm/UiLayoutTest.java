package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 主界面层级与对话框外观（v1.22.4）。
 *
 * <p>这几条都是「看起来对不对」的问题，没有业务逻辑，但改布局时很容易被挪回去，
 * 所以用测试钉住：总开关在列表之上、文案是「闹钟总开关」、对话框是圆角底。
 *
 * <p>v1.22.5 补：首页倒计时真的在走、总开关关掉时如实说「已关闭」、
 * 日历里点灰字日期翻到那个月而不是给看不见的日子打标记。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class UiLayoutTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private MainActivity launch() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    /** 总开关是「总指挥」，必须排在闹钟列表之前（v1.22.4 从列表下方上移）。 */
    @Test
    public void masterSwitch_sitsAboveAlarmList() {
        MainActivity a = launch();
        View rowSwitch = a.findViewById(R.id.row_alarm_switch);
        View list = a.findViewById(R.id.alarm_list);
        assertNotNull(rowSwitch);
        assertNotNull(list);
        ViewGroup card = (ViewGroup) list.getParent();
        assertTrue("总开关应与列表在同一张卡片里", card == rowSwitch.getParent());
        int switchIndex = card.indexOfChild(rowSwitch);
        int listIndex = card.indexOfChild(list);
        assertTrue("总开关（" + switchIndex + "）应在闹钟列表（" + listIndex + "）之前",
                switchIndex < listIndex);
    }


    private static String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append('|');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(collectText(g.getChildAt(i)));
        }
        return sb.toString();
    }

    /**
     * 改完闹钟时间回到主界面，列表和倒计时必须都跟着变（v1.25.3）。
     *
     * <p>以前列表用 onCreate 的快照、倒计时重读设置，改一次就分叉一次：
     * 用户把起床从 07:30 改成 07:00，列表还写 07:30，顶部却说「下次 07:00」。
     */
    @Test
    public void afterEditingAlarm_listAndHeroAgree() {
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MainActivity a = ctl.get();
        Prefs.setEnabled(ctx(), true);

        java.util.List<Alarm> alarms = new java.util.ArrayList<>(Prefs.getAlarms(ctx()));
        assertFalse("预置闹钟不该是空的", alarms.isEmpty());
        Alarm first = alarms.get(0);
        alarms.set(0, new Alarm(first.id, 7, 0, first.label, Alarm.Mode.WORKDAY, true));
        Prefs.setAlarms(ctx(), alarms);

        ctl.pause().resume();
        ShadowLooper.idleMainLooper();

        String list = collectText(a.findViewById(R.id.alarm_list));
        assertTrue("列表要显示改后的 07:00，实际：" + list, list.contains("07:00"));
        assertFalse("列表不该还留着改前的 07:30，实际：" + list, list.contains("07:30"));

        TextView hero = a.findViewById(R.id.tv_today_sub);
        assertTrue("倒计时卡片也要是 07:00，实际：" + hero.getText(),
                hero.getText().toString().contains("07:00"));
    }

    /** 名字从「闹钟开关」改成「闹钟总开关」，避免被当成单个闹钟的开关。 */
    @Test
    public void masterSwitch_isNamedZongKaiGuan() {
        MainActivity a = launch();
        ViewGroup row = a.findViewById(R.id.row_alarm_switch);
        TextView title = (TextView) ((ViewGroup) row.getChildAt(0)).getChildAt(0);
        assertTrue("标题应写明是总开关，实际：" + title.getText(),
                title.getText().toString().contains("闹钟总开关"));
    }

    /** 对话框圆角：窗口背景被换成 24dp 圆角的 shape，不再是直角白块。 */
    @Test
    public void dialogs_haveRoundedBackground() {
        Prefs.setLastRunVersion(ctx(), 54);   // 触发升级弹窗
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出更新日志", d);
        Drawable bg = d.getWindow().getDecorView().getBackground();
        assertTrue("对话框背景应是圆角 shape，实际：" + bg, bg instanceof GradientDrawable);
        float radius = ((GradientDrawable) bg).getCornerRadius();
        assertTrue("圆角半径应大于 0，实际：" + radius, radius > 0);
    }

    /** 倒计时从「下一次闹钟」算起：设一个 2 小时后的闹钟，数字应落在 01:59:5x ~ 02:00:00。 */
    @Test
    public void countdown_countsDownToNextAlarm() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        LocalDateTime target = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        // 关掉「首次读取补默认闹钟」的迁移：否则刚写的测试闹钟会被默认的 07:00 / 13:40 顶掉
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit()
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true).commit();
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, target.getHour(), target.getMinute(), "测试", Alarm.Mode.DAILY, true)));
        Prefs.setEnabled(ctx(), true);

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        TextView cd = ctl.get().findViewById(R.id.tv_countdown);
        assertNotNull("首页应有倒计时数字", cd);
        assertTrue("倒计时应是时:分:秒，实际：" + cd.getText(),
                cd.getText().toString().matches("\\d{2}:\\d{2}:\\d{2}"));

        long left = Duration.between(LocalDateTime.now(), target).getSeconds();
        long shown = parseHms(cd.getText().toString());
        assertTrue("倒计时应等于剩余时间（±3 秒），剩余 " + left + " 显示 " + shown,
                Math.abs(left - shown) <= 3);
        ctl.pause().stop().destroy();
    }

    /** 总开关关着：不能还显示「下一个 13:40」让人以为会响。 */
    @Test
    public void countdown_whenMasterOff_saysClosed() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setEnabled(ctx(), false);

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        TextView sub = ctl.get().findViewById(R.id.tv_today_sub);
        TextView cd = ctl.get().findViewById(R.id.tv_countdown);
        assertTrue("副标题要说明总开关关着，实际：" + sub.getText(),
                sub.getText().toString().contains("闹钟总开关已关闭"));
        assertEquals("--:--:--", cd.getText().toString());
        ctl.pause().stop().destroy();
    }

    /** 闹钟行不再各自套一张描边小卡：列表里只有「行 + 分隔线」，行数 = 闹钟数 × 2 - 1。 */
    @Test
    public void alarmRows_areFlatListNotNestedCards() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        int rows = Prefs.getAlarms(ctx()).size();
        assertEquals("应为「行 + 分隔线」交替，实际子 View 数：" + list.getChildCount(),
                Math.max(1, rows * 2 - 1), list.getChildCount());
    }

    /** 点日历里的灰字日期（上 / 下月）应该翻到那个月，而不是给一个看不见的日子打标记。 */
    @Test
    public void clickingOutOfMonthCell_jumpsToThatMonth() {
        // v1.34：日历回到首页，直接在主界面视图树里验
        MainActivity a = launch();
        ShadowLooper.idleMainLooper();
        ViewGroup rows = a.findViewById(R.id.cal_rows);
        ViewGroup lastRow = (ViewGroup) rows.getChildAt(rows.getChildCount() - 1);
        View lastCell = lastRow.getChildAt(lastRow.getChildCount() - 1);
        lastCell.performClick();
        ShadowLooper.idleMainLooper();

        LocalDate first = LocalDate.now().withDayOfMonth(1);
        LocalDate start = WeekSchedule.startOfWeek(first, Prefs.isSundayFirst(ctx()));
        LocalDate expected = start.plusDays(41);
        TextView title = a.findViewById(R.id.tv_cal_title);
        assertEquals(expected.getYear() + "年" + expected.getMonthValue() + "月",
                title.getText().toString());
    }

    private static long parseHms(String s) {
        String[] p = s.split(":");
        return Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2]);
    }

    /** v1.23：排班与闹钟拆成两张卡——两张卡各有标题，不再靠小节标签分组。 */
    @Test
    public void scheduleAndAlarm_areTwoSeparateCards() {
        MainActivity a = launch();
        View weekMode = a.findViewById(R.id.row_week_mode);
        View list = a.findViewById(R.id.alarm_list);
        assertTrue("排班行与闹钟列表不该在同一张卡里",
                weekMode.getParent() != list.getParent());
        // 大小周下：周休制度行 + 「本周是 / 小周休哪天」那一组，共 2 个子 View
        assertEquals("排班卡只装排班设置", 2,
                ((ViewGroup) weekMode.getParent()).getChildCount());
    }

    /**
     * v1.23.9：整行贴顶。
     *
     * <p>26sp 的时间块（35dp）比「名称 + 规则」两行块（38dp）略矮，贴顶时两者字形上沿齐平；
     * 居中会让时间整体下沉 3dp 左右，看着像「没跟名称站在一条线上」。
     */
    @Test
    public void alarmRow_alignsTimeWithNameAtTop() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        LinearLayout row = (LinearLayout) list.getChildAt(0);
        assertEquals("时间与名称应顶部对齐", Gravity.TOP,
                row.getGravity() & Gravity.VERTICAL_GRAVITY_MASK);
    }

    /**
     * v1.23.9：卡头那条线跟行间的线落在同一条竖线上。
     *
     * <p>卡头线在卡片里、行间线在列表里，列表自己还有 16dp 的左内边距，
     * 两边算出来的绝对起点必须相等，否则三条线一长一短看着零碎。
     */
    @Test
    public void alarmCard_topDividerAlignsWithRowDividers() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        View innerDiv = list.getChildAt(1);
        View topDiv = a.findViewById(R.id.div_alarm_top);
        assertNotNull("卡头分隔线必须存在", topDiv);

        int innerStart = ((LinearLayout.LayoutParams) innerDiv.getLayoutParams()).getMarginStart();
        int topStart = ((LinearLayout.LayoutParams) topDiv.getLayoutParams()).getMarginStart();
        int listPad = (int) (16 * a.getResources().getDisplayMetrics().density);
        assertEquals("卡头分隔线应与行间分隔线落在同一条竖线上",
                innerStart + listPad, topStart);
    }

    /**
     * v1.23.9：分隔线缩进到名称左边缘，不通栏。
     *
     * <p>通栏的线把卡片切成两半；缩进到内容起点之后，行与行才像「同一组里的两条」。
     * 这里比的是参数关系——分隔线的 marginStart 必须等于「时间列宽 + 名称前间距」。
     */
    @Test
    public void alarmRows_dividerIndentsToNameColumn() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        LinearLayout row = (LinearLayout) list.getChildAt(0);
        View divider = list.getChildAt(1);
        View time = row.getChildAt(0);
        View mid = row.getChildAt(1);

        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        LinearLayout.LayoutParams mlp = (LinearLayout.LayoutParams) mid.getLayoutParams();
        assertEquals("分隔线起点应与名称起点重合",
                time.getLayoutParams().width + mlp.getMarginStart(), dlp.getMarginStart());
    }

    /**
     * v1.23.3：时间列必须定宽。
     *
     * <p>上一版时间还是 wrap_content，宽度跟着内容走：「1」比「0」窄，于是 07:00 和 15:31
     * 的右边缘不在一处，右边名称的起点一行一个位置——两个闹钟摆在一起，名称是错开的。
     */
    @Test
    public void alarmRows_timeColumnIsFixedWidth() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        // 默认两个闹钟，中间夹一条 1dp 分隔线
        View time0 = ((LinearLayout) list.getChildAt(0)).getChildAt(0);
        View time1 = ((LinearLayout) list.getChildAt(2)).getChildAt(0);

        assertTrue("时间列不能是 wrap_content，否则宽度随内容变",
                time0.getLayoutParams().width > 0);
        assertEquals("两行的时间列必须同宽，名称起点才会对齐",
                time0.getLayoutParams().width, time1.getLayoutParams().width);
        // 定宽得放得下最宽的数字组合，不能把「15:31」挤出去
        TextView t = (TextView) time0;
        assertTrue("时间列宽度要放得下 5 个数字",
                t.getPaint().measureText("88:88") <= time0.getLayoutParams().width);
    }

    /**
     * 运行状态默认收起（v1.28）。
     *
     * <p>这里以前常驻三行小字（提醒总数、大周基准、节假日数据来源），占了主界面最底部
     * 近 100dp。收进折叠卡后，用户默认只看到「已开启 · N 个提醒」一行，
     * 想核对细节再展开——不能默认展开，否则等于没改。
     */
    @Test
    public void runStatus_collapsedByDefault_withOneLineSummary() {
        MainActivity a = launch();
        assertEquals("运行状态默认应收起", View.GONE,
                a.findViewById(R.id.run_body).getVisibility());
        TextView summary = a.findViewById(R.id.tv_run_summary);
        assertNotNull(summary);
        String text = summary.getText().toString();
        assertTrue("摘要要能看出开关状态，实际：" + text,
                text.startsWith("已开启") || text.startsWith("已关闭"));
        // 详情仍要在展开区里，别把信息删了
        assertNotNull(a.findViewById(R.id.tv_status));
        assertEquals("收起时按钮写「展开」", "展开",
                ((TextView) a.findViewById(R.id.tv_run_toggle)).getText().toString());
    }

    /** 请假 / 加班两个入口并排一行（v1.28）：原来是两座通栏「小山」。 */
    @Test
    public void vacationAndOvertime_shareOneRow() {
        MainActivity a = launch();
        ShadowLooper.idleMainLooper();
        View vacation = a.findViewById(R.id.tv_vacation);
        View overtime = a.findViewById(R.id.tv_overtime);
        assertTrue("两者应在同一个横向容器里", vacation.getParent() == overtime.getParent());
        LinearLayout row = (LinearLayout) vacation.getParent();
        assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
        assertEquals("等宽两列", 2, row.getChildCount());
        assertEquals(((LinearLayout.LayoutParams) vacation.getLayoutParams()).weight,
                ((LinearLayout.LayoutParams) overtime.getLayoutParams()).weight, 0.001f);
    }
}
